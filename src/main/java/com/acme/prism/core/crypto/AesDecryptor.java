package com.acme.prism.core.crypto;

import cn.hutool.core.util.StrUtil;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-CBC 密文解密器：解密密文（Base64），输出明文。
 *
 * <p>算法约定与既有 CryptoJS 解密工具（decrypt.html）一致：密钥与 IV 同源
 * （IV 取密钥前 16 字节，CBC 块大小）、PKCS7 填充（与 JDK {@code AES/CBC/PKCS5Padding}
 * 对 16 字节块等价）。解密结果期望为 JSON，由调用方格式化展示。</p>
 *
 * <p>兼容处理：部分密文尾部填充值超过块大小（如 21 字节 0x15），
 * CryptoJS 宽松接受并直接剥离，JDK 严格模式会拒绝——本类在严格模式失败后回退
 * NoPadding 解密 + 手动剥离（校验尾部填充字节一致），对齐 CryptoJS 行为。</p>
 *
 * <p>密钥由调用方注入（插件设置），本类保持平台无关以便单元测试。</p>
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
public final class AesDecryptor {

    /**
     * CBC 块大小（字节）
     */
    private static final int CBC_BLOCK_SIZE = 16;
    /**
     * 字节无符号掩码（byte → 0~255）
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * JDK AES-CBC 严格填充算法名（PKCS5 与 CryptoJS 的 PKCS7 对 16 字节块等价）
     */
    private static final String ALGORITHM_PKCS5 = "AES/CBC/PKCS5Padding";
    /**
     * JDK AES-CBC 无填充算法名（回退路径手动剥离填充）
     */
    private static final String ALGORITHM_NO_PADDING = "AES/CBC/NoPadding";

    private AesDecryptor() {
    }

    /**
     * 解密 Base64 密文为明文。
     *
     * @param base64Cipher Base64 密文
     * @param key          AES 密钥（UTF-8 编码后须为 16/24/32 字节）
     * @return 明文；输入为空、密钥非法、非 Base64 或解密失败时返回 {@code null}
     */
    public static String decrypt(final String base64Cipher, final String key) {
        if (StrUtil.isBlank(base64Cipher) || StrUtil.isBlank(key)) {
            return null;
        }
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        // IV 与密钥同源：CryptoJS 显式传入 32 字节 IV 时按 CBC 块大小截取前 16 字节
        final byte[] ivBytes = Arrays.copyOf(keyBytes, CBC_BLOCK_SIZE);
        try {
            final byte[] cipherBytes = Base64.getDecoder().decode(base64Cipher.trim());
            return decryptStrict(keyBytes, ivBytes, cipherBytes);
        } catch (final IllegalArgumentException ignored) {
            // 非法 Base64
            return null;
        }
    }

    /**
     * 严格 PKCS5 解密；填充校验失败时回退 NoPadding + 手动剥离（对齐 CryptoJS 宽松行为）。
     *
     * @param keyBytes    密钥字节
     * @param ivBytes     IV 字节
     * @param cipherBytes 密文字节
     * @return 明文；解密失败返回 {@code null}
     */
    private static String decryptStrict(final byte[] keyBytes, final byte[] ivBytes, final byte[] cipherBytes) {
        try {
            final Cipher cipher = Cipher.getInstance(ALGORITHM_PKCS5);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (final BadPaddingException ignored) {
            // 兼容路径：密文尾部填充值可能超过块大小（如 21 字节 0x15），
            // CryptoJS 宽松剥离，JDK 严格模式拒绝——回退 NoPadding 手动剥离
            return decryptLenient(keyBytes, ivBytes, cipherBytes);
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * 宽松解密：NoPadding 解出原始字节后按尾部填充值手动剥离（校验尾部字节一致）。
     *
     * @param keyBytes    密钥字节
     * @param ivBytes     IV 字节
     * @param cipherBytes 密文字节
     * @return 明文；解密失败或填充结构非法返回 {@code null}
     */
    private static String decryptLenient(final byte[] keyBytes, final byte[] ivBytes, final byte[] cipherBytes) {
        try {
            final Cipher cipher = Cipher.getInstance(ALGORITHM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
            final byte[] raw = cipher.doFinal(cipherBytes);
            if (raw.length == 0) {
                return null;
            }
            final int padding = raw[raw.length - 1] & BYTE_MASK;
            if (padding < 1 || padding > raw.length) {
                return null;
            }
            // 校验尾部 padding 字节全部等于填充值（防止误剥真实数据）
            for (int i = raw.length - padding; i < raw.length; i++) {
                if ((raw[i] & BYTE_MASK) != padding) {
                    return null;
                }
            }
            return new String(Arrays.copyOfRange(raw, 0, raw.length - padding), StandardCharsets.UTF_8);
        } catch (final Exception ignored) {
            return null;
        }
    }
}
