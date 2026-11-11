package com.xnullcat.app;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sambungan lewat internet ke HP anak (Firebase Realtime Database).
 * Jalur: /p/KODE/state (diisi HP anak, dibaca di sini) dan /p/KODE/cmds (diisi di sini, dibaca HP anak).
 * Semua panggilan ke Sink dijalankan lewat executor "main".
 */
final class NetLink {
    interface Sink {
        void opened();

        /** Keadaan atau daftar aplikasi, formatnya sama dengan pesan Bluetooth. */
        void line(String json);

        /** Cap waktu server dari HP anak. Berubah terus selama HP anak online. */
        void seen(long value);

        void name(String name);

        /** Foto baru (base64 JPEG) dari HP anak. */
        void photo(String base64);

        void failed(String message, boolean permanent);
    }

    private final String code;
    private final Executor main;
    private final Sink sink;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private Rtdb.Stream stream;

    NetLink(String code, Executor main, Sink sink) {
        this.code = code;
        this.main = main;
        this.sink = sink;
    }

    private String root() {
        return "/p/" + code;
    }

    void start() {
        stream = Rtdb.stream(root() + "/state", new Rtdb.Events() {
            @Override
            public void onOpen() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        sink.opened();
                    }
                });
            }

            @Override
            public void onEvent(String event, String data) {
                if ("cancel".equals(event) || "auth_revoked".equals(event)) {
                    fail(friendly(403), true);
                    return;
                }
                if ("put".equals(event) || "patch".equals(event)) {
                    handle(data);
                }
            }

            @Override
            public void onError(int httpCode, String message, boolean everOpened) {
                fail(friendly(httpCode), !everOpened);
            }
        });
    }

    private static String friendly(int httpCode) {
        if (httpCode == 401 || httpCode == 403) {
            return "Database Firebase menolak akses. Periksa Rules di Firebase Console.";
        }
        if (httpCode == 404) {
            return "Alamat database tidak ditemukan.";
        }
        return "Tidak bisa terhubung lewat internet. Cek koneksi.";
    }

    private void fail(final String msg, final boolean permanent) {
        post(new Runnable() {
            @Override
            public void run() {
                sink.failed(msg, permanent);
            }
        });
        if (permanent) {
            close(); // setelah pesan terkirim, karena close() menahan pesan berikutnya
        }
    }

    private void post(Runnable r) {
        if (!closed) {
            main.execute(r);
        }
    }

    private void handle(String raw) {
        try {
            JSONObject d = new JSONObject(raw);
            String path = d.optString("path", "");
            Object data = d.opt("data");
            if ("/".equals(path)) {
                if (data instanceof JSONObject) {
                    JSONObject o = (JSONObject) data;
                    // "seen" paling akhir supaya keadaan sudah terbaca saat status online berubah
                    apply("s", o.opt("s"));
                    apply("apps", o.opt("apps"));
                    apply("name", o.opt("name"));
                    apply("photo", o.opt("photo"));
                    apply("seen", o.opt("seen"));
                }
            } else if (path.length() > 1 && path.indexOf('/', 1) < 0) {
                apply(path.substring(1), data);
            }
        } catch (Exception ignored) {
            // kejadian rusak diabaikan
        }
    }

    private void apply(String key, Object v) {
        if (v == null || v == JSONObject.NULL) {
            return;
        }
        if (("s".equals(key) || "apps".equals(key)) && v instanceof String) {
            final String json = (String) v;
            post(new Runnable() {
                @Override
                public void run() {
                    sink.line(json);
                }
            });
        } else if ("seen".equals(key) && v instanceof Number) {
            final long value = ((Number) v).longValue();
            post(new Runnable() {
                @Override
                public void run() {
                    sink.seen(value);
                }
            });
        } else if ("name".equals(key) && v instanceof String) {
            final String n = (String) v;
            post(new Runnable() {
                @Override
                public void run() {
                    sink.name(n);
                }
            });
        } else if ("photo".equals(key) && v instanceof String) {
            final String b64 = (String) v;
            post(new Runnable() {
                @Override
                public void run() {
                    sink.photo(b64);
                }
            });
        }
    }

    /** Menulis perintah. Dijalankan di thread terpisah. */
    void send(String cmd, String pkg) {
        send(cmd, pkg, null, null);
    }

    void send(final String cmd, final String pkg, final String msg, final String pw) {
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
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
                        Rtdb.post(root() + "/cmds", o.toString());
                    } catch (Exception ignored) {
                        // perintah gagal terkirim
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // sudah ditutup
        }
    }

    void close() {
        closed = true;
        Rtdb.Stream s = stream;
        if (s != null) {
            s.close();
        }
        io.shutdown();
    }
}
