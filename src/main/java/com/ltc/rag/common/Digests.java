package com.ltc.rag.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具。内容寻址（去重/变更检测）全靠它。
 */
public final class Digests {

    private Digests() {
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
