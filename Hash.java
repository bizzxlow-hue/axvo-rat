package com.xnullcat.app;

import java.security.MessageDigest;

/** Password Lock Screen dikirim sebagai hash SHA-256, jadi teks aslinya tidak lewat internet. */
final class Hash {
    private Hash() {
    }

    static String of(String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(("jaga:" + pw).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean valid(String h) {
        return h != null && h.matches("[0-9a-f]{64}");
    }

    static boolean same(String a, String b) {
        try {
            return MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }
}
