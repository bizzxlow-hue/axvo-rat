package com.xnullcat.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Klien kecil untuk Firebase Realtime Database lewat REST (tanpa SDK dan tanpa google-services.json).
 * Hanya memakai java.net, jadi bisa diuji di komputer.
 */
final class Rtdb {
    static volatile String base = "https://abxrat-default-rtdb.asia-southeast1.firebasedatabase.app";

    interface Events {
        /** Aliran berhasil dibuka (juga setiap kali tersambung lagi). */
        void onOpen();

        /** Satu kejadian SSE. data berupa teks JSON mentah. */
        void onEvent(String event, String data);

        /**
         * Gagal atau terputus. Kalau everOpened false, aliran berhenti sendiri dan tidak mencoba lagi.
         * Kalau true, aliran akan mencoba tersambung lagi sendiri.
         */
        void onError(int httpCode, String message, boolean everOpened);
    }

    /** Aliran kejadian. Panggil close() untuk menghentikan. */
    static final class Stream {
        private volatile boolean stop;
        private volatile HttpURLConnection conn;
        private volatile Thread thread;

        void close() {
            stop = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                    // sudah putus
                }
            }
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }

        private void run(String path, Events ev) {
            int fails = 0;
            boolean opened = false;
            while (!stop) {
                HttpURLConnection c = null;
                try {
                    c = open(path);
                    conn = c;
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(75000); // Firebase mengirim tanda hidup tiap 30 detik
                    int code = c.getResponseCode();
                    if (code != 200) {
                        if (!opened) {
                            ev.onError(code, "HTTP " + code, false);
                            return;
                        }
                        throw new java.io.IOException("HTTP " + code);
                    }
                    opened = true;
                    fails = 0;
                    ev.onOpen();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    String event = null;
                    StringBuilder data = new StringBuilder();
                    while (!stop && (line = r.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (event != null) {
                                ev.onEvent(event, data.toString());
                            }
                            event = null;
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                    if (!stop) {
                        ev.onError(0, "Aliran berhenti", true);
                    }
                } catch (Exception e) {
                    if (stop) {
                        return;
                    }
                    if (!opened) {
                        ev.onError(0, String.valueOf(e.getMessage()), false);
                        return;
                    }
                    ev.onError(0, String.valueOf(e.getMessage()), true);
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Exception ignored) {
                            // sudah putus
                        }
                    }
                }
                if (stop) {
                    return;
                }
                fails++;
                long wait = Math.min(30000L, 2000L << Math.min(fails, 4));
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    static Stream stream(final String path, final Events ev) {
        final Stream s = new Stream();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                s.run(path, ev);
            }
        }, "rtdb-stream");
        t.setDaemon(true);
        s.thread = t;
        t.start();
        return s;
    }

    /** Menulis nilai JSON ke path. Mengembalikan true kalau berhasil. */
    static boolean put(String path, String json) {
        return write("PUT", path, json) != null;
    }

    /** Menambah anak baru dengan kunci otomatis. Mengembalikan true kalau berhasil. */
    static boolean post(String path, String json) {
        return write("POST", path, json) != null;
    }

    static boolean delete(String path) {
        return write("DELETE", path, null) != null;
    }

    private static HttpURLConnection open(String path) throws java.io.IOException {
        return (HttpURLConnection) new URL(base + path + ".json").openConnection();
    }

    private static String write(String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = open(path);
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] b = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                in.close();
            }
            return code / 100 == 2 ? bo.toString("UTF-8") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
