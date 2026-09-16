package com.agentscopea2a.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 通知签名占位实现。
 */
public class SignUtil {
    public String generateSign(String timestamp, String nonce, String appKey, String appSecret) {
        String source = String.valueOf(timestamp) + String.valueOf(nonce)
                + String.valueOf(appKey) + String.valueOf(appSecret);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
