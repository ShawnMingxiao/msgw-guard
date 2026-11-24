package com.msgwguard.util;

public final class Hex {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(DIGITS[(b & 0xF0) >>> 4]);
            sb.append(DIGITS[b & 0x0F]);
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        if (hex == null || hex.trim().length() == 0) {
            return null;
        }
        String s = hex.trim();
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string: " + hex);
        }
        byte[] result = new byte[s.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(s.charAt(i * 2), 16);
            int low = Character.digit(s.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex string: " + hex);
            }
            result[i] = (byte) ((high << 4) + low);
        }
        return result;
    }
}
