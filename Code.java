package com.xnullcat.app;

import java.security.SecureRandom;

/** Kode pairing: 16 karakter acak (sekitar 80 bit), tanpa huruf yang mirip (I, O, 0, 1). */
final class Code {
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 16;

    private Code() {
    }

    static String generate() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Membersihkan ketikan pengguna. Mengembalikan null kalau bukan kode yang sah. */
    static String normalize(String in) {
        if (in == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : in.toUpperCase().toCharArray()) {
            if (ch == ' ' || ch == '-' || ch == '\n' || ch == '\t') {
                continue;
            }
            if (ALPHABET.indexOf(ch) < 0) {
                return null;
            }
            sb.append(ch);
        }
        return sb.length() == LENGTH ? sb.toString() : null;
    }

    /** ABCD-EFGH-JKLM-NPQR */
    static String pretty(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }
}
