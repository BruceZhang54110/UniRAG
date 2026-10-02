package com.unirag.ingestion.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文档内容指纹（research.md R4）：{@code SHA-256(内容 UTF-8 字节)} 的 hex 串。
 *
 * <p>语义：同内容必同指纹（FR-006 重复摄取识别的裁决依据），冲突概率可忽略；JDK 内置实现，无新依赖。
 */
public final class DocumentFingerprint {

    private DocumentFingerprint() {
    }

    /**
     * 计算内容的 SHA-256 指纹（64 字符小写 hex）。
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 强制实现算法，不可能到达
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
