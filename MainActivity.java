package com.xnullcat.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;

/** AVXO: aplikasi di HP orang tua untuk mengontrol HP anak lewat Bluetooth. */
public class MainActivity extends Activity implements Link.Listener {

    private static final int REQ_PERM = 11;
    private static final int REQ_ENABLE = 12;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // semua animasi sengaja lambat
    private static final long OUT_MS = 280;
    private static final long IN_MS = 700;
    private static final long STEP_MS = 120;
    private static final long DIALOG_IN_MS = 560;
    private static final long DIALOG_OUT_MS = 300;

    private static final class Dev {
        final BluetoothDevice dev;
        final String addr;
        String name;

        Dev(BluetoothDevice dev, String name) {
            this.dev = dev;
            this.addr = dev.getAddress();
            this.name = name;
        }
    }

    /** HP anak yang disimpan untuk kontrol lewat internet. */
    private static final class NetDev {
        final String code;
        String name;

        NetDev(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private final ArrayList<NetDev> nets = new ArrayList<>();
    private BluetoothAdapter adapter;
    private Link link;
    private LinearLayout root;
    private FrameLayout content;
    private int screen = -1; // 0 = Home, 1 = Device
    private boolean swapping;
    private final ArrayList<Dev> devices = new ArrayList<>();
    private LinearLayout deviceList;
    private TextView hpStatus;
    private TextView scanBtn;
    private TextView homeStatus;
    private boolean scanning;
    private String pendingBond;
    private Dialog dialog;
    private View dialogBody;
    private Runnable dialogRefresh;
    private boolean closing;

    // ------------------------------------------------------------------ siklus hidup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        link = new Link(adapter);
        link.listener = this;
        loadNets();

        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, f);

        go(0);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // sudah dilepas
        }
        stopScan();
        link.listener = null;
        link.disconnect();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            return;
        }
        if (screen == 1) {
            go(0);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ util

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ pindah layar (efek lambat)

    private void go(int target) {
        if (swapping || target == screen) {
            return;
        }
        if (screen == 1) {
            stopScan();
        }
        dismissDialog();
        screen = target;
        if (target == 0) {
            ScrollView sv = buildHome();
            swap(sv, (ViewGroup) sv.getChildAt(0), null);
        } else {
            LinearLayout col = buildHp();
            swap(col, col, new Runnable() {
                @Override
                public void run() {
                    startHpFlow();
                }
            });
        }
    }

    private void swap(final View next, final ViewGroup staggerTarget, final Runnable afterShow) {
        final View old = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        final Runnable put = new Runnable() {
            @Override
            public void run() {
                content.removeAllViews();
                content.addView(next, new FrameLayout.LayoutParams(MATCH, MATCH));
                swapping = false;
                stagger(staggerTarget);
                if (afterShow != null) {
                    afterShow.run();
                }
            }
        };
        swapping = true;
        if (old == null) {
            put.run();
        } else {
            old.animate().alpha(0f).translationY(dp(10)).setDuration(OUT_MS)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(put).start();
        }
    }

    /** Isi layar muncul satu per satu dengan gerak pelan. */
    private void stagger(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            c.setAlpha(0f);
            c.setTranslationY(dp(28));
            c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(STEP_MS * i).setDuration(IN_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    // ------------------------------------------------------------------ layar Home

    private ScrollView buildHome() {
        deviceList = null;
        hpStatus = null;
        scanBtn = null;

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(24));

        col.addView(Ui.sectionHead(this, Ui.NEWS, "News", v -> openMenu()), lp(MATCH, WRAP, 0, 8, 0, 14));
        col.addView(buildBanner(), lp(MATCH, WRAP, 0, 0, 0, 0));

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        View dot = new View(this);
        dot.setBackground(Ui.box(this, Ui.INK, 4, 0, 0));
        dots.addView(dot, new LinearLayout.LayoutParams(dp(42), dp(7)));
        col.addView(dots, lp(MATCH, WRAP, 0, 18, 0, 0));

        homeStatus = Ui.text(this, "", 12.5f, Ui.SOFT, false, true);
        homeStatus.setGravity(Gravity.CENTER);
        homeStatus.setLetterSpacing(0.06f);
        col.addView(homeStatus, lp(MATCH, WRAP, 4, 26, 4, 0));

        sv.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        updateHomeStatus();
        return sv;
    }

    private View buildBanner() {
        // bingkai putih tipis di belakang supaya kartu terasa melayang, mirip kartu di foto referensi
        FrameLayout shell = new FrameLayout(this);
        shell.setBackground(Ui.box(this, Ui.SURFACE, 34, 0, 0));
        shell.setPadding(dp(5), dp(5), dp(5), dp(5));
        shell.setElevation(dp(10));
        Ui.clipRound(shell, dp(34));

        Ui.AspectFrame banner = new Ui.AspectFrame(this, 1.65f);
        banner.setBackgroundColor(0xFF20242C);
        Ui.clipRound(banner, dp(29));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try (InputStream in = getAssets().open("banner.jpg")) {
            img.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
            // tanpa foto, latar gelap tetap tampil
        }
        banner.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2030610, 0xB3030610, 0x33030610, 0x00030610}));
        banner.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(22), 0, dp(22), dp(20));
        TextView t1 = Ui.text(this, "AVXO", 23, Color.WHITE, true, true);
        t1.setLetterSpacing(0.02f);
        t1.setShadowLayer(dp(10), 0, dp(2), 0x59000000);
        TextView t2 = Ui.text(this, "Developer @Bizzishere", 12.5f, 0xCCFFFFFF, false, true);
        t2.setLetterSpacing(0.05f);
        copy.addView(t1, lp(WRAP, WRAP, 0, 0, 0, 8));
        copy.addView(t2, lp(WRAP, WRAP, 0, 0, 0, 0));
        banner.addView(copy, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        shell.addView(banner, new FrameLayout.LayoutParams(MATCH, MATCH));
        return shell;
    }

    private void updateHomeStatus() {
        if (homeStatus == null) {
            return;
        }
        if (link.state == Link.CONNECTED) {
            if (link.net) {
                homeStatus.setText("Tersambung lewat internet ke " + link.name + (link.online ? "" : " (offline)"));
            } else {
                homeStatus.setText("Tersambung ke " + link.name);
            }
            homeStatus.setTextColor(link.net && !link.online ? Ui.SOFT : Ui.GREEN);
        } else if (link.state == Link.CONNECTING) {
            homeStatus.setText("Menyambung…");
            homeStatus.setTextColor(Ui.SOFT);
        } else {
            homeStatus.setText("Belum tersambung. Tekan tombol di samping News, lalu pilih Device.");
            homeStatus.setTextColor(Ui.SOFT);
        }
    }

    // ------------------------------------------------------------------ layar Device (daftar Bluetooth)

    private LinearLayout buildHp() {
        homeStatus = null;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout head = Ui.sectionHead(this, Ui.PHONE, "Device", v -> openMenu());
        header.addView(head, new LinearLayout.LayoutParams(0, WRAP, 1f));

        scanBtn = Ui.text(this, "Cari", 14, Ui.INK, true, false);
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        scanBtn.setBackground(Ui.box(this, Ui.SURFACE, 20, Ui.INK, 1.5f));
        scanBtn.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else {
                startScan();
            }
        });
        header.addView(scanBtn, new LinearLayout.LayoutParams(WRAP, WRAP));
        col.addView(header, lp(MATCH, WRAP, 0, 8, 0, 14));

        hpStatus = Ui.text(this, "", 12.5f, Ui.SOFT, false, true);
        hpStatus.setLineSpacing(0, 1.15f);
        col.addView(hpStatus, lp(MATCH, WRAP, 4, 0, 4, 14));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, 0, 0, dp(12));
        sv.addView(deviceList, new FrameLayout.LayoutParams(MATCH, WRAP));
        col.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        renderScan();
        renderDevices();
        return col;
    }

    private void startHpFlow() {
        if (adapter == null) {
            setHpStatus("HP ini tidak punya Bluetooth. Pakai kode internet dari Jaga Anak.");
        } else if (!hasPerms()) {
            setHpStatus("Izinkan Bluetooth dulu supaya daftar HP bisa muncul.");
            requestPermissions(needed(), REQ_PERM);
        } else {
            afterPerms();
        }
    }

    private String[] needed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : needed()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM || screen != 1) {
            return;
        }
        if (hasPerms()) {
            afterPerms();
        } else {
            setHpStatus("Izin Bluetooth ditolak. Aktifkan lewat Pengaturan, Aplikasi, AVXO, Izin.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ENABLE && screen == 1) {
            if (adapter != null && adapter.isEnabled()) {
                afterPerms();
            } else {
                setHpStatus("Bluetooth belum menyala.");
            }
        }
    }

    private void afterPerms() {
        if (adapter == null) {
            return;
        }
        if (!adapter.isEnabled()) {
            setHpStatus("Menyalakan Bluetooth…");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                putDevice(d);
            }
        }
        renderDevices();
        if (link.state == Link.CONNECTED) {
            setHpStatus("Tersambung ke " + link.name + ". Tekan namanya untuk membuka fitur.");
        } else {
            setHpStatus("Pilih HP anak lewat Bluetooth (di HP anak tekan tombol Bluetooth dulu), atau tambah lewat kode internet untuk kontrol dari jauh.");
            startScan();
        }
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled() || !hasPerms()) {
            return;
        }
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        scanning = adapter.startDiscovery();
        if (!scanning) {
            setHpStatus("Tidak bisa mulai mencari. Cek izin Bluetooth dan lokasi.");
        }
        renderScan();
    }

    private void stopScan() {
        if (adapter != null && hasPerms()) {
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // izin dicabut
            }
        }
        scanning = false;
        renderScan();
    }

    private void renderScan() {
        if (scanBtn != null) {
            scanBtn.setText(scanning ? "Berhenti" : "Cari");
        }
    }

    private void setHpStatus(String s) {
        if (hpStatus != null) {
            hpStatus.setText(s);
        }
    }

    private String nameOf(BluetoothDevice d) {
        String n = null;
        try {
            n = d.getName();
        } catch (SecurityException ignored) {
            // izin belum ada
        }
        return (n == null || n.trim().isEmpty()) ? "Tanpa nama" : n;
    }

    private void putDevice(BluetoothDevice d) {
        String n = nameOf(d);
        for (Dev x : devices) {
            if (x.addr.equals(d.getAddress())) {
                if (!"Tanpa nama".equals(n)) {
                    x.name = n;
                }
                return;
            }
        }
        devices.add(new Dev(d, n));
    }

    private int rank(Dev d) {
        if (link.state != Link.IDLE && d.addr.equals(link.address)) {
            return 0;
        }
        return d.dev.getBondState() == BluetoothDevice.BOND_BONDED ? 1 : 2;
    }

    private TextView sectionLabel(String t) {
        TextView v = Ui.text(this, t.toUpperCase(), 11, Ui.SOFT, true, false);
        v.setLetterSpacing(0.16f);
        return v;
    }

    private void renderDevices() {
        if (deviceList == null) {
            return;
        }
        deviceList.removeAllViews();

        deviceList.addView(sectionLabel("Internet"), lp(MATCH, WRAP, 4, 0, 0, 8));
        for (final NetDev n : nets) {
            deviceList.addView(netRow(n), lp(MATCH, WRAP, 0, 0, 0, 10));
        }
        deviceList.addView(addRow(), lp(MATCH, WRAP, 0, 0, 0, 20));

        deviceList.addView(sectionLabel("Bluetooth"), lp(MATCH, WRAP, 4, 0, 0, 8));
        if (devices.isEmpty()) {
            TextView empty = Ui.text(this, "Belum ada HP Bluetooth. Tekan Cari.", 13, Ui.SOFT, false, true);
            empty.setGravity(Gravity.CENTER);
            deviceList.addView(empty, lp(MATCH, WRAP, 0, 14, 0, 0));
            return;
        }
        ArrayList<Dev> sorted = new ArrayList<>(devices);
        Collections.sort(sorted, new Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                int r = rank(a) - rank(b);
                return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
            }
        });
        for (final Dev d : sorted) {
            deviceList.addView(deviceRow(d), lp(MATCH, WRAP, 0, 0, 0, 10));
        }
    }

    // ---- kontrol lewat internet

    private void loadNets() {
        nets.clear();
        try {
            SharedPreferences p = getSharedPreferences("net", MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String c = Code.normalize(o.optString("c"));
                if (c != null) {
                    nets.add(new NetDev(c, o.optString("n", "HP anak")));
                }
            }
        } catch (Exception ignored) {
            // daftar rusak: mulai kosong
        }
    }

    private void saveNets() {
        try {
            JSONArray a = new JSONArray();
            for (NetDev n : nets) {
                a.put(new JSONObject().put("c", n.code).put("n", n.name));
            }
            getSharedPreferences("net", MODE_PRIVATE).edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
            // gagal menyimpan: daftar tetap ada selama aplikasi terbuka
        }
    }

    private boolean isMine(NetDev n) {
        return link.net && link.address.equals("net:" + n.code);
    }

    private View addRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        r.setPadding(dp(14), dp(14), dp(14), dp(14));
        r.setBackground(Ui.box(this, Ui.SURFACE, 22, Ui.EDGE, 1));
        r.setClickable(true);
        Ui.pressable(r);
        r.setOnClickListener(v -> openAddCode());
        r.addView(Ui.text(this, "+  Tambah lewat kode", 14, Ui.INK, true, false));
        return r;
    }

    private View netRow(final NetDev n) {
        boolean mine = isMine(n);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(14), dp(14), dp(14));
        r.setBackground(Ui.box(this, Ui.SURFACE, 22, Ui.EDGE, 1));
        r.setElevation(dp(2));
        r.setClickable(true);
        Ui.pressable(r);
        r.setOnClickListener(v -> onNetTap(n));
        r.setOnLongClickListener(v -> {
            openRemoveNet(n);
            return true;
        });

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.box(this, Ui.BG, 16, Ui.EDGE, 1));
        ico.addView(new Ui.IconView(this, Ui.PHONE, Ui.INK), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        r.addView(ico, lp(dp(48), dp(48), 0, 0, 14, 0));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, n.name, 16, Ui.INK, true, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = Ui.text(this, "Internet · kode …" + n.code.substring(n.code.length() - 4), 11, Ui.SOFT, false, true);
        mid.addView(name, lp(MATCH, WRAP, 0, 0, 0, 5));
        mid.addView(sub, lp(WRAP, WRAP, 0, 0, 0, 0));
        r.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

        String state = null;
        int color = Ui.SOFT;
        boolean bold = false;
        if (mine && link.state == Link.CONNECTED) {
            state = link.online ? "● Online" : "○ Offline";
            color = link.online ? Ui.GREEN : Ui.SOFT;
            bold = link.online;
        } else if (mine && link.state == Link.CONNECTING) {
            state = "Menyambung…";
        }
        if (state != null) {
            r.addView(Ui.text(this, state, 12, color, bold, true), lp(WRAP, WRAP, 10, 0, 0, 0));
        }
        return r;
    }

    private void onNetTap(NetDev n) {
        boolean mine = isMine(n);
        if (mine && link.state == Link.CONNECTED) {
            openFeatures();
            return;
        }
        if (mine && link.state == Link.CONNECTING) {
            toast("Sedang menyambung, tunggu sebentar.");
            return;
        }
        stopScan();
        if (link.state != Link.IDLE) {
            link.disconnect();
        }
        setHpStatus("Menyambung lewat internet ke " + n.name + "…");
        link.connectNet(n.code, n.name);
        renderDevices();
    }

    private void addNet(String code) {
        NetDev found = null;
        for (NetDev n : nets) {
            if (n.code.equals(code)) {
                found = n;
            }
        }
        if (found == null) {
            found = new NetDev(code, "HP anak");
            nets.add(found);
            saveNets();
        }
        renderDevices();
        onNetTap(found);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    private void openAddCode() {
        if (closing) {
            return;
        }
        LinearLayout body = card();
        body.addView(Ui.text(this, "Tambah HP anak", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        body.addView(Ui.text(this, "Ketik kode dari aplikasi Jaga Anak.", 11.5f, Ui.SOFT, false, true), lp(MATCH, WRAP, 0, 0, 0, 10));

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        et.setHint("XXXX-XXXX-XXXX-XXXX");
        et.setHintTextColor(Ui.SOFT);
        et.setTextColor(Ui.INK);
        et.setTextSize(14);
        et.setTypeface(Typeface.MONOSPACE);
        et.setBackground(Ui.box(this, Ui.BG, 14, Ui.EDGE, 1));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.addView(et, lp(MATCH, WRAP, 0, 0, 0, 6));

        final TextView err = Ui.text(this, "", 11, 0xFFB3261E, false, true);
        body.addView(err, lp(MATCH, WRAP, 2, 0, 2, 2));

        TextView paste = linkText("Tempel dari salinan");
        paste.setOnClickListener(v -> {
            String code = clipboardCode();
            if (code == null) {
                err.setText("Tidak ada kode di salinan. Salin dulu dari Jaga Anak.");
                return;
            }
            err.setText("");
            et.setText(Code.pretty(code));
            et.setSelection(et.getText().length());
        });
        body.addView(paste, lp(MATCH, WRAP, 0, 0, 0, 4));

        LinearLayout row = new LinearLayout(this);
        TextView cancel = pill("Batal", false);
        TextView add = pill("Tambah", true);
        cancel.setOnClickListener(v -> {
            hideKeyboard(et);
            closeDialog();
        });
        add.setOnClickListener(v -> {
            final String code = Code.normalize(et.getText().toString());
            if (code == null) {
                err.setText("Kode harus 16 huruf dan angka (tanpa I, O, 0, 1).");
                return;
            }
            hideKeyboard(et);
            closeDialog(new Runnable() {
                @Override
                public void run() {
                    addNet(code);
                }
            });
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(add, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));
        showCard(body);
        // kalau kode sudah ada di papan salinan (misalnya dari tombol Salin kode di Jaga Anak), isi otomatis
        String clip = clipboardCode();
        if (clip != null) {
            et.setText(Code.pretty(clip));
            et.setSelection(et.getText().length());
        } else {
            et.postDelayed(new Runnable() {
                @Override
                public void run() {
                    et.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            }, 450);
        }
    }

    /** Kode yang sah dari papan salinan, atau null kalau tidak ada / bukan kode yang valid. */
    private String clipboardCode() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
                return null;
            }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return t == null ? null : Code.normalize(t.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void openRemoveNet(final NetDev n) {
        if (closing) {
            return;
        }
        LinearLayout body = card();
        body.addView(Ui.text(this, "Hapus HP ini?", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        body.addView(Ui.text(this, n.name + " dihapus dari daftar. Kodenya bisa dimasukkan lagi nanti.", 11.5f, Ui.SOFT, false, true), lp(MATCH, WRAP, 0, 0, 0, 12));
        LinearLayout row = new LinearLayout(this);
        TextView cancel = pill("Batal", false);
        TextView del = pill("Hapus", true);
        cancel.setOnClickListener(v -> closeDialog());
        del.setOnClickListener(v -> closeDialog(new Runnable() {
            @Override
            public void run() {
                if (isMine(n)) {
                    link.disconnect();
                }
                nets.remove(n);
                saveNets();
                renderDevices();
            }
        }));
        row.addView(cancel, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(del, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));
        showCard(body);
    }

    private View deviceRow(final Dev d) {
        boolean mine = d.addr.equals(link.address);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(14), dp(14), dp(14));
        r.setBackground(Ui.box(this, Ui.SURFACE, 22, Ui.EDGE, 1));
        r.setElevation(dp(2));
        r.setClickable(true);
        Ui.pressable(r);
        r.setOnClickListener(v -> onDeviceTap(d));

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.box(this, Ui.BG, 16, Ui.EDGE, 1));
        ico.addView(new Ui.IconView(this, Ui.PHONE, Ui.INK), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        r.addView(ico, lp(dp(48), dp(48), 0, 0, 14, 0));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, d.name, 16, Ui.INK, true, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView addr = Ui.text(this, d.addr, 11, Ui.SOFT, false, true);
        mid.addView(name, lp(MATCH, WRAP, 0, 0, 0, 5));
        mid.addView(addr, lp(WRAP, WRAP, 0, 0, 0, 0));
        r.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

        String state = null;
        int color = Ui.SOFT;
        if (mine && link.state == Link.CONNECTED) {
            state = "● Tersambung";
            color = Ui.GREEN;
        } else if (mine && link.state == Link.CONNECTING) {
            state = "Menyambung…";
        } else if (d.addr.equals(pendingBond)) {
            state = "Memasangkan…";
        } else if (d.dev.getBondState() == BluetoothDevice.BOND_BONDED) {
            state = "Terpasang";
        }
        if (state != null) {
            TextView st = Ui.text(this, state, 12, color, mine && link.state == Link.CONNECTED, true);
            r.addView(st, lp(WRAP, WRAP, 10, 0, 0, 0));
        }
        return r;
    }

    private void onDeviceTap(Dev d) {
        if (link.state == Link.CONNECTED && d.addr.equals(link.address)) {
            openFeatures();
            return;
        }
        if (link.state == Link.CONNECTING) {
            toast("Sedang menyambung, tunggu sebentar.");
            return;
        }
        stopScan();
        if (link.state == Link.CONNECTED) {
            link.disconnect();
        }
        if (d.dev.getBondState() == BluetoothDevice.BOND_NONE) {
            pendingBond = d.addr;
            setHpStatus("Memasangkan dengan " + d.name + ". Setujui permintaan pairing di kedua HP.");
            if (!d.dev.createBond()) {
                pendingBond = null;
                setHpStatus("Tidak bisa memulai pairing dengan " + d.name + ".");
            }
        } else {
            setHpStatus("Menyambung ke " + d.name + "…");
            link.connect(d.dev, d.name);
        }
        renderDevices();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    putDevice(d);
                    renderDevices();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                scanning = false;
                renderScan();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (d != null && pendingBond != null && pendingBond.equals(d.getAddress())) {
                    if (st == BluetoothDevice.BOND_BONDED) {
                        pendingBond = null;
                        setHpStatus("Terpasang. Menyambung…");
                        link.connect(d, nameOf(d));
                    } else if (st == BluetoothDevice.BOND_NONE) {
                        pendingBond = null;
                        setHpStatus("Pairing dibatalkan atau gagal. Coba tekan namanya lagi.");
                    }
                }
                renderDevices();
            }
        }
    };

    // ------------------------------------------------------------------ perubahan dari sambungan

    @Override
    public void onLinkChanged() {
        if (link.net && link.state == Link.CONNECTED && link.address.startsWith("net:")) {
            String c = link.address.substring(4);
            for (NetDev n : nets) {
                if (n.code.equals(c) && !n.name.equals(link.name)) {
                    n.name = link.name;
                    saveNets();
                }
            }
        }
        updateHomeStatus();
        renderDevices();
        if (link.state == Link.CONNECTED) {
            setHpStatus((link.net ? "Tersambung lewat internet ke " : "Tersambung ke ") + link.name + ". Tekan namanya untuk membuka fitur.");
        } else if (link.state == Link.IDLE && link.error != null) {
            setHpStatus(link.error);
        }
        if (dialog != null && dialog.isShowing()) {
            if (link.state != Link.CONNECTED) {
                dialog.dismiss();
                toast("Sambungan ke HP anak terputus.");
            } else if (dialogRefresh != null) {
                dialogRefresh.run();
            }
        }
    }

    // ------------------------------------------------------------------ kotak kecil (dialog)

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(10));
        c.setBackground(Ui.box(this, Ui.SURFACE, 22, Ui.EDGE, 1));
        return c;
    }

    private int dialogWidth() {
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.66f);
        return Math.max(dp(232), Math.min(dp(290), w));
    }

    /** Menampilkan kotak kecil. Kalau sudah ada kotak, isinya diganti tanpa berkedip. */
    private void showCard(View body) {
        dialogRefresh = null;
        if (dialog == null || !dialog.isShowing()) {
            final Dialog d = new Dialog(this);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setContentView(body);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.4f);
            }
            d.setOnDismissListener(x -> {
                if (dialog == d) {
                    dialog = null;
                    dialogBody = null;
                    dialogRefresh = null;
                    closing = false;
                }
            });
            d.show();
            if (w != null) {
                w.setLayout(dialogWidth(), WRAP);
            }
            dialog = d;
        } else {
            dialog.setContentView(body);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(dialogWidth(), WRAP);
            }
        }
        dialogBody = body;
        body.setAlpha(0f);
        body.setScaleX(0.8f);
        body.setScaleY(0.8f);
        body.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(DIALOG_IN_MS)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void dismissDialog() {
        if (dialog != null) {
            Dialog d = dialog;
            dialog = null;
            dialogBody = null;
            dialogRefresh = null;
            closing = false;
            d.dismiss();
        }
    }

    /** Menutup kotak kecil dengan memudar pelan. */
    private void closeDialog() {
        closeDialog(null);
    }

    private void closeDialog(final Runnable after) {
        if (closing) {
            return;
        }
        if (dialog == null || dialogBody == null) {
            dismissDialog();
            if (after != null) {
                after.run();
            }
            return;
        }
        closing = true;
        final Dialog d = dialog;
        dialogRefresh = null;
        dialogBody.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(DIALOG_OUT_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        closing = false;
                        if (d.isShowing()) {
                            d.dismiss();
                        }
                        if (after != null) {
                            after.run();
                        }
                    }
                }).start();
    }

    private TextView pill(String text, boolean filled) {
        TextView t = Ui.text(this, text, 13, filled ? Color.WHITE : Ui.INK, true, false);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        t.setBackground(Ui.box(this, filled ? Ui.INK : Ui.SURFACE, 14, Ui.INK, 1.5f));
        t.setClickable(true);
        Ui.pressable(t);
        return t;
    }

    private TextView linkText(String text) {
        TextView t = Ui.text(this, text, 12, Ui.SOFT, true, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(9), dp(8), dp(4));
        t.setClickable(true);
        return t;
    }

    private LinearLayout featureRow(int icon, String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(10), dp(10));
        r.setBackground(Ui.box(this, Ui.BG, 16, Ui.EDGE, 1));
        r.setClickable(true);
        r.setOnClickListener(click);
        Ui.pressable(r);

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.box(this, Ui.SURFACE, 12, Ui.EDGE, 1));
        ico.addView(new Ui.IconView(this, icon, Ui.INK), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        r.addView(ico, lp(dp(36), dp(36), 0, 0, 10, 0));

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, 14, Ui.INK, true, false), lp(WRAP, WRAP, 0, 0, 0, 3));
        t.addView(Ui.text(this, sub, 10.5f, Ui.SOFT, false, true), lp(WRAP, WRAP, 0, 0, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return r;
    }

    /** Kotak kecil berisi tombol Home dan Device. */
    private void openMenu() {
        if (closing || swapping) {
            return;
        }
        LinearLayout body = card();
        body.addView(Ui.text(this, "Menu", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 10));
        body.addView(featureRow(Ui.HOME, "Home", screen == 0 ? "Sedang dibuka" : "Beranda",
                v -> goFromMenu(0)), lp(MATCH, WRAP, 0, 0, 0, 8));
        body.addView(featureRow(Ui.PHONE, "Device", screen == 1 ? "Sedang dibuka" : "Daftar HP anak",
                v -> goFromMenu(1)), lp(MATCH, WRAP, 0, 0, 0, 2));
        TextView close = linkText("Tutup");
        close.setOnClickListener(v -> closeDialog());
        body.addView(close, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);
    }

    private void goFromMenu(final int target) {
        closeDialog(new Runnable() {
            @Override
            public void run() {
                go(target);
            }
        });
    }

    private void openFeatures() {
        LinearLayout body = card();
        TextView title = Ui.text(this, link.name, 16, Ui.INK, true, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title, lp(MATCH, WRAP, 0, 0, 0, 4));
        final TextView status = Ui.text(this, "● Tersambung", 10.5f, Ui.GREEN, true, true);
        body.addView(status, lp(WRAP, WRAP, 0, 0, 0, 10));

        // video kecil memanjang ke kiri dan kanan, di atas fitur
        Ui.AspectFrame strip = new Ui.AspectFrame(this, 2.8f);
        strip.setBackgroundColor(0xFF20242C);
        Ui.clipRound(strip, dp(12));
        strip.addView(new LoopVideo(this, "clip.mp4"), new FrameLayout.LayoutParams(MATCH, MATCH));
        body.addView(strip, lp(MATCH, WRAP, 0, 0, 0, 10));

        // daftar fitur bisa discroll supaya kotaknya tidak makin tinggi kalau fiturnya bertambah
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.addView(featureRow(Ui.GRID, "Lock App", "Kunci aplikasi", v -> openLockApp()), lp(MATCH, WRAP, 0, 0, 0, 8));
        rows.addView(featureRow(Ui.SCREEN, "Lock Screen", "Kunci layar", v -> openLockScreen()), lp(MATCH, WRAP, 0, 0, 0, 8));
        rows.addView(featureRow(Ui.WARN, "Prank Virus", "Alarm + video horor", v -> openPrank()), lp(MATCH, WRAP, 0, 0, 0, 8));
        rows.addView(featureRow(Ui.WARN, "Virus V2", "Video kedua + senter kelap-kelip", v -> openVirus2()), lp(MATCH, WRAP, 0, 0, 0, 8));
        rows.addView(featureRow(Ui.WARN, "Prank Audio", "Suara menyeramkan", v -> openAudioPrank()), lp(MATCH, WRAP, 0, 0, 0, 8));
        rows.addView(featureRow(Ui.CAMERA, "Kamera", "Ambil foto dari HP anak", v -> openCamera()), lp(MATCH, WRAP, 0, 0, 0, 0));
        int rowsMax = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
        Ui.CappedScrollView rowsScroll = new Ui.CappedScrollView(this, rowsMax);
        rowsScroll.addView(rows, new FrameLayout.LayoutParams(MATCH, WRAP));
        body.addView(rowsScroll, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView disconnect = linkText("Putuskan");
        disconnect.setOnClickListener(v -> {
            closeDialog();
            link.disconnect();
        });
        body.addView(disconnect, lp(MATCH, WRAP, 0, 4, 0, 0));
        TextView close = linkText("Tutup");
        close.setOnClickListener(v -> closeDialog());
        body.addView(close, lp(MATCH, WRAP, 0, 0, 0, 0));
        showCard(body);

        dialogRefresh = () -> {
            if (link.net && !link.online) {
                status.setText("○ Offline · perintah dikirim saat online");
                status.setTextColor(Ui.SOFT);
            } else {
                status.setText(link.net ? "● Online" : "● Tersambung");
                status.setTextColor(Ui.GREEN);
            }
        };
        dialogRefresh.run();
    }

    private EditText field(String hint, int max) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        et.setHint(hint);
        et.setHintTextColor(Ui.SOFT);
        et.setTextColor(Ui.INK);
        et.setTextSize(13);
        et.setBackground(Ui.box(this, Ui.BG, 14, Ui.EDGE, 1));
        et.setPadding(dp(12), dp(9), dp(12), dp(9));
        return et;
    }

    private void openLockScreen() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Lock Screen", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView stat = Ui.text(this, "", 11.5f, Ui.SOFT, false, true);
        body.addView(stat, lp(MATCH, WRAP, 0, 0, 0, 10));

        final SharedPreferences sp = getSharedPreferences("lock", MODE_PRIVATE);
        final EditText msg = field("Pesan di layar anak", 120);
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        msg.setText(sp.getString("msg", ""));
        body.addView(msg, lp(MATCH, WRAP, 0, 0, 0, 8));
        final EditText pw = field("Password (boleh kosong)", 16);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pw.setText(sp.getString("pw", ""));
        body.addView(pw, lp(MATCH, WRAP, 0, 0, 0, 10));

        LinearLayout row = new LinearLayout(this);
        TextView lock = pill("Lock", true);
        TextView unlock = pill("Unlock", false);
        lock.setOnClickListener(v -> {
            String m = msg.getText().toString().trim();
            String p = pw.getText().toString();
            sp.edit().putString("msg", m).putString("pw", p).apply();
            hideKeyboard(msg);
            // password dikirim sebagai hash, teks aslinya tidak keluar dari HP ini
            link.send("lockScreen", null, m, p.isEmpty() ? "" : Hash.of(p));
        });
        unlock.setOnClickListener(v -> {
            hideKeyboard(msg);
            link.send("unlockScreen", null);
        });
        row.addView(lock, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(unlock, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> {
            hideKeyboard(msg);
            openFeatures();
        });
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        dialogRefresh = () -> {
            String base = link.screenLocked ? "Layar anak: terkunci" : "Layar anak: terbuka";
            stat.setText(link.net && !link.online ? base + " (HP anak offline, perintah menunggu)" : base);
            stat.setTextColor(link.screenLocked ? Ui.INK : Ui.SOFT);
        };
        dialogRefresh.run();
        link.send("status", null);
    }

    private void openPrank() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Prank Virus", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView note = Ui.text(this, "", 11.5f, Ui.SOFT, false, true);
        note.setLineSpacing(0, 1.15f);
        body.addView(note, lp(MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout row = new LinearLayout(this);
        TextView start = pill("Mulai", true);
        TextView stop = pill("Stop", false);
        start.setOnClickListener(v -> {
            link.send("prank", null);
            toast("Prank dikirim ke HP anak.");
        });
        stop.setOnClickListener(v -> link.send("prankStop", null));
        row.addView(start, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> openFeatures());
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);

        dialogRefresh = () -> note.setText(link.net && !link.online
                ? "HP anak offline. Prank jalan saat HP anak online lagi."
                : "Di HP anak muncul alarm virus palsu, lalu video horor, lalu tulisan Cuma prank. Berhenti sendiri.");
        dialogRefresh.run();
    }

    private void openVirus2() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Virus V2", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView note = Ui.text(this, "", 11.5f, Ui.SOFT, false, true);
        note.setLineSpacing(0, 1.15f);
        body.addView(note, lp(MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout row = new LinearLayout(this);
        TextView start = pill("Mulai", true);
        TextView stop = pill("Stop", false);
        start.setOnClickListener(v -> {
            link.send("virus2", null);
            toast("Virus V2 dikirim ke HP anak.");
        });
        stop.setOnClickListener(v -> link.send("prankStop", null));
        row.addView(start, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> openFeatures());
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);

        dialogRefresh = () -> note.setText(link.net && !link.online
                ? "HP anak offline. Virus V2 jalan saat HP anak online lagi."
                : "Di HP anak muncul alarm, lalu video horor kedua dengan suara, diulang terus sambil senter berkedip-kedip. Tekan Stop di sini untuk mematikannya. Kalau HP anak tidak punya senter, bagian itu dilewati saja.");
        dialogRefresh.run();
    }

    private void openAudioPrank() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Prank Audio", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView note = Ui.text(this, "", 11.5f, Ui.SOFT, false, true);
        note.setLineSpacing(0, 1.15f);
        body.addView(note, lp(MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout row = new LinearLayout(this);
        TextView start = pill("Mulai", true);
        TextView stop = pill("Stop", false);
        start.setOnClickListener(v -> {
            link.send("audio", null);
            toast("Suara dikirim ke HP anak.");
        });
        stop.setOnClickListener(v -> link.send("audioStop", null));
        row.addView(start, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(row, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> openFeatures());
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);

        dialogRefresh = () -> note.setText(link.net && !link.online
                ? "HP anak offline. Suara jalan saat HP anak online lagi."
                : "Di HP anak muncul suara menyeramkan, diulang terus tanpa tampilan apa pun. Tekan Stop di sini untuk mematikannya.");
        dialogRefresh.run();
    }

    private void openCamera() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Kamera", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView note = Ui.text(this, "", 11.5f, Ui.SOFT, false, true);
        note.setLineSpacing(0, 1.15f);
        body.addView(note, lp(MATCH, WRAP, 0, 0, 0, 10));

        Ui.AspectFrame photoFrame = new Ui.AspectFrame(this, 1.0f);
        photoFrame.setBackgroundColor(0xFF20242C);
        Ui.clipRound(photoFrame, dp(12));
        final ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoFrame.addView(photo, new FrameLayout.LayoutParams(MATCH, MATCH));
        body.addView(photoFrame, lp(MATCH, WRAP, 0, 0, 0, 10));

        TextView take = pill("Ambil Foto", true);
        take.setOnClickListener(v -> {
            link.send("photo", null);
            toast("Perintah ambil foto dikirim ke HP anak.");
        });
        body.addView(take, lp(MATCH, WRAP, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> openFeatures());
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);

        final int[] shownSeq = {-1};
        dialogRefresh = () -> {
            note.setText(link.net && !link.online
                    ? "HP anak offline. Foto diambil saat HP anak online lagi."
                    : "Saat foto diambil, HP anak menampilkan tanda kamera aktif (titik hijau) di status bar, seperti aplikasi kamera lain.");
            if (link.photoB64 != null && link.photoSeq != shownSeq[0]) {
                shownSeq[0] = link.photoSeq;
                try {
                    byte[] bytes = android.util.Base64.decode(link.photoB64, android.util.Base64.DEFAULT);
                    photo.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
                } catch (Exception ignored) {
                    // foto rusak, diabaikan
                }
            }
        };
        dialogRefresh.run();
    }

    private void openLockApp() {
        LinearLayout body = card();
        body.addView(Ui.text(this, "Lock App", 16, Ui.INK, true, false), lp(MATCH, WRAP, 0, 0, 0, 6));
        final TextView stat = Ui.text(this, "", 10.5f, Ui.SOFT, false, true);
        body.addView(stat, lp(MATCH, WRAP, 0, 0, 0, 10));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackground(Ui.box(this, Ui.BG, 16, Ui.EDGE, 1));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
        body.addView(sv, lp(MATCH, h, 0, 0, 0, 2));

        TextView back = linkText("Kembali");
        back.setOnClickListener(v -> openFeatures());
        body.addView(back, lp(MATCH, WRAP, 0, 4, 0, 0));
        showCard(body);

        dialogRefresh = () -> renderApps(list, stat);
        dialogRefresh.run();
        link.send("listApps", null);
    }

    private void renderApps(LinearLayout list, TextView stat) {
        list.removeAllViews();
        if (link.apps == null) {
            stat.setText(link.net && !link.online
                    ? "HP anak offline. Daftar dimuat saat HP anak online."
                    : "Memuat daftar aplikasi…");
            return;
        }
        if (link.apps.isEmpty()) {
            stat.setText("Tidak ada aplikasi yang bisa dikunci.");
            return;
        }
        stat.setText("Ketuk untuk kunci, ketuk lagi untuk buka.");
        for (final Link.App a : link.apps) {
            final boolean locked = link.lockedApps.contains(a.pkg);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(9), dp(10), dp(9));
            r.setClickable(true);
            r.setBackground(Ui.box(this, locked ? Ui.SURFACE : Color.TRANSPARENT, 12, Ui.EDGE, locked ? 1 : 0));
            r.setOnClickListener(v -> link.send(locked ? "unlockApp" : "lockApp", a.pkg));
            TextView n = Ui.text(this, a.name, 13, Ui.INK, locked, false);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, new LinearLayout.LayoutParams(0, WRAP, 1f));
            if (locked) {
                TextView badge = Ui.text(this, "Terkunci", 10, Color.WHITE, true, false);
                badge.setPadding(dp(8), dp(4), dp(8), dp(4));
                badge.setBackground(Ui.box(this, Ui.INK, 9, 0, 0));
                r.addView(badge, lp(WRAP, WRAP, 6, 0, 0, 0));
            }
            list.addView(r, lp(MATCH, WRAP, 0, 0, 0, 1));
        }
    }
}
