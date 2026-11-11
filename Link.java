package com.xnullcat.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sambungan Bluetooth ke HP anak (aplikasi Jaga Anak).
 * Pesan berupa satu baris JSON per perintah. Semua field publik hanya dibaca dan diubah di main thread.
 */
final class Link {
    static final UUID APP_UUID = UUID.fromString("8f3b6c1e-5d2a-4e7b-9a41-2c6d0b7e9f10");
    static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2;

    interface Listener {
        void onLinkChanged();
    }

    static final class App {
        final String name;
        final String pkg;

        App(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    Listener listener;
    int state = IDLE;
    String name = "";
    String address = "";
    String error = null;
    boolean net; // true = lewat internet (Firebase), false = Bluetooth
    boolean online = true; // lewat internet: HP anak sedang aktif
    boolean screenLocked;
    final Set<String> lockedApps = new HashSet<>();
    List<App> apps; // null = belum dimuat
    String photoB64; // null = belum ada foto
    int photoSeq; // bertambah tiap foto baru datang, supaya tampilan tahu kapan harus digambar ulang

    private BluetoothSocket socket;
    private OutputStream out;
    private volatile int generation;
    private NetLink netLink;
    private boolean haveSeen;
    private long lastSeen;
    private long lastChange;
    private static final long ONLINE_MS = 75000; // HP anak mengabari tiap 30 detik

    Link(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    /** Dipanggil dari main thread. */
    void connect(final BluetoothDevice dev, String label) {
        final int gen;
        synchronized (this) {
            closeSocketLocked();
            closeNetLocked();
            gen = ++generation;
        }
        net = false;
        online = true;
        state = CONNECTING;
        name = label;
        address = dev.getAddress();
        error = null;
        screenLocked = false;
        lockedApps.clear();
        photoB64 = null;
        apps = null;
        notifyChanged();
        new Thread(new Runnable() {
            @Override
            public void run() {
                runConnection(dev, gen);
            }
        }, "link-" + gen).start();
    }

    /** Menyambung lewat internet memakai kode dari HP anak. Dipanggil dari main thread. */
    void connectNet(final String code, String label) {
        final int gen;
        synchronized (this) {
            closeSocketLocked();
            closeNetLocked();
            gen = ++generation;
        }
        net = true;
        online = false;
        haveSeen = false;
        state = CONNECTING;
        name = label;
        address = "net:" + code;
        error = null;
        screenLocked = false;
        lockedApps.clear();
        photoB64 = null;
        apps = null;
        notifyChanged();
        final NetLink nl = new NetLink(code, new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable r) {
                main.post(r);
            }
        }, new NetLink.Sink() {
            @Override
            public void opened() {
                if (gen != generation) {
                    return;
                }
                state = CONNECTED;
                error = null;
                notifyChanged();
                send("status", null); // supaya HP anak langsung mengabari keadaannya
                main.postDelayed(expire, 10000);
            }

            @Override
            public void line(String json) {
                if (gen == generation) {
                    handle(json, gen);
                }
            }

            @Override
            public void seen(long value) {
                if (gen != generation) {
                    return;
                }
                if (!haveSeen) {
                    haveSeen = true; // nilai pertama hanya patokan, bisa saja sudah lama
                } else if (value != lastSeen) {
                    lastChange = SystemClock.elapsedRealtime();
                    if (!online) {
                        online = true;
                        notifyChanged();
                    }
                }
                lastSeen = value;
            }

            @Override
            public void name(String n) {
                if (gen == generation && n != null && !n.isEmpty() && !n.equals(name)) {
                    name = n;
                    notifyChanged();
                }
            }

            @Override
            public void photo(String base64) {
                if (gen == generation && base64 != null && !base64.isEmpty()) {
                    photoB64 = base64;
                    photoSeq++;
                    notifyChanged();
                }
            }

            @Override
            public void failed(String message, boolean permanent) {
                if (gen != generation) {
                    return;
                }
                if (permanent) {
                    state = IDLE;
                    error = message;
                    screenLocked = false;
                    lockedApps.clear();
                    photoB64 = null;
                    apps = null;
                } else {
                    online = false; // terputus sebentar, aliran mencoba lagi sendiri
                }
                notifyChanged();
            }
        });
        netLink = nl;
        nl.start();
    }

    /** Mengecek tiap beberapa detik apakah HP anak masih mengabari. */
    private final Runnable expire = new Runnable() {
        @Override
        public void run() {
            if (!net || state == IDLE) {
                return;
            }
            if (online && SystemClock.elapsedRealtime() - lastChange > ONLINE_MS) {
                online = false;
                notifyChanged();
            }
            main.postDelayed(this, 10000);
        }
    };

    /** Dipanggil dari main thread. */
    void disconnect() {
        synchronized (this) {
            generation++;
            closeSocketLocked();
            closeNetLocked();
        }
        net = false;
        online = true;
        state = IDLE;
        error = null;
        screenLocked = false;
        lockedApps.clear();
        photoB64 = null;
        apps = null;
        notifyChanged();
    }

    void send(String cmd, String pkg) {
        send(cmd, pkg, null, null);
    }

    /** msg dan pw (hash) dipakai oleh perintah lockScreen. */
    void send(String cmd, String pkg, String msg, String pw) {
        if (net) {
            NetLink nl = netLink;
            if (nl != null) {
                nl.send(cmd, pkg, msg, pw);
            }
            return;
        }
        final String line = buildLine(cmd, pkg, msg, pw);
        if (line == null) {
            return;
        }
        final int gen = generation;
        writer.execute(new Runnable() {
            @Override
            public void run() {
                OutputStream o;
                synchronized (Link.this) {
                    o = out;
                }
                if (o == null) {
                    return;
                }
                try {
                    o.write(line.getBytes("UTF-8"));
                    o.flush();
                } catch (IOException e) {
                    synchronized (Link.this) {
                        closeSocketLocked();
                    }
                    fail(gen, "Sambungan terputus.");
                }
            }
        });
    }

    private static String buildLine(String cmd, String pkg, String msg, String pw) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", cmd);
            if (pkg != null) {
                o.put("pkg", pkg);
            }
            if (msg != null) {
                o.put("msg", msg);
            }
            if (pw != null) {
                o.put("pw", pw);
            }
            return o.toString() + "\n";
        } catch (Exception e) {
            return null;
        }
    }

    private void runConnection(BluetoothDevice dev, int gen) {
        BluetoothSocket s = null;
        boolean connected = false;
        try {
            if (adapter != null) {
                adapter.cancelDiscovery();
            }
            s = dev.createRfcommSocketToServiceRecord(APP_UUID);
            s.connect();
            synchronized (this) {
                if (gen != generation) {
                    closeQuietly(s);
                    return;
                }
                socket = s;
                out = s.getOutputStream();
            }
            connected = true;
            postIfCurrent(gen, new Runnable() {
                @Override
                public void run() {
                    state = CONNECTED;
                    error = null;
                }
            });
            send("status", null);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                handle(line, gen);
            }
            fail(gen, "Sambungan terputus.");
        } catch (Exception e) {
            closeQuietly(s);
            fail(gen, connected
                    ? "Sambungan terputus."
                    : "Gagal menyambung. Pastikan Jaga Anak di HP anak sudah menekan tombol Bluetooth, lalu coba lagi.");
        }
    }

    private void handle(String line, int gen) {
        try {
            JSONObject o = new JSONObject(line);
            final String type = o.optString("type");
            final boolean sl = o.optBoolean("screenLocked", false);
            final Set<String> la = new HashSet<>();
            JSONArray ja = o.optJSONArray("lockedApps");
            if (ja != null) {
                for (int i = 0; i < ja.length(); i++) {
                    la.add(ja.getString(i));
                }
            }
            final List<App> list = "apps".equals(type) ? parseApps(o) : null;
            final String photo = "photo".equals(type) ? o.optString("photo", null) : null;
            postIfCurrent(gen, new Runnable() {
                @Override
                public void run() {
                    if ("state".equals(type) || "apps".equals(type)) {
                        screenLocked = sl;
                        lockedApps.clear();
                        lockedApps.addAll(la);
                    }
                    if (list != null) {
                        apps = list;
                    }
                    if (photo != null) {
                        photoB64 = photo;
                        photoSeq++;
                    }
                }
            });
        } catch (Exception ignored) {
            // baris rusak diabaikan
        }
    }

    private static List<App> parseApps(JSONObject o) throws Exception {
        List<App> list = new ArrayList<>();
        JSONArray arr = o.optJSONArray("apps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.getJSONObject(i);
                list.add(new App(x.getString("n"), x.getString("p")));
            }
        }
        return list;
    }

    private void postIfCurrent(final int gen, final Runnable change) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) {
                    change.run();
                    notifyChanged();
                }
            }
        });
    }

    private void fail(final int gen, final String message) {
        postIfCurrent(gen, new Runnable() {
            @Override
            public void run() {
                state = IDLE;
                error = message;
                screenLocked = false;
                lockedApps.clear();
                photoB64 = null;
                apps = null;
            }
        });
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onLinkChanged();
        }
    }

    private void closeNetLocked() {
        if (netLink != null) {
            netLink.close();
            netLink = null;
        }
        main.removeCallbacks(expire);
    }

    private void closeSocketLocked() {
        if (socket != null) {
            closeQuietly(socket);
        }
        socket = null;
        out = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // tidak ada yang perlu dilakukan
        }
    }
}
