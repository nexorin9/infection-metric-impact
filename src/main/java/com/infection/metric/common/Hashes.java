package com.infection.metric.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希工具。口径指纹、幂等键与缓存键都走这里，保证「同内容 = 同哈希」全链路一致。
 */
public final class Hashes {

    private Hashes() {
    }

    /** 以 {@code |} 连接各段后取 sha256 十六进制摘要。幂等键与指纹都用这个形式。 */
    public static String sha256Of(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append('|');
            }
            joined.append(parts[i] == null ? "" : parts[i]);
        }
        return sha256(joined.toString());
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现，无法计算内容哈希", e);
        }
    }
}
