package com.acme.prism.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES-256-CBC 密文解密器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
class AesDecryptorTest {

    /**
     * 测试专用密钥（32 字节 ASCII，与生产密钥无关，仅供单测）
     */
    private static final String KEY = "UnitTestAesKey1234567890abcdefgh";
    /**
     * CBC 块大小（字节）
     */
    private static final int CBC_BLOCK_SIZE = 16;
    /**
     * JDK AES-CBC 算法名
     */
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    @Test
    @DisplayName("互操作：openssl 生成的密文可解密（与 CryptoJS 同语义）")
    void decryptsOpensslVector() {
        // 向量来源：openssl enc -aes-256-cbc -K <测试密钥hex> -iv <前16字节hex> -nosalt -base64
        // CryptoJS 显式传 iv 时同样输出纯 ciphertext 且 IV 截断为块大小，两者互操作等价
        final String cipher = "I2jDBv/3m2+Ha924OxDhT/6wUZyzr2pj8riSoheQFxM=";
        final String plain = AesDecryptor.decrypt(cipher, KEY);
        assertNotNull(plain, "合法密文应解密成功");
        assertEquals("{\"name\":\"张三\",\"age\":18}", plain);
    }

    @Test
    @DisplayName("正常：round-trip 加密后解密还原")
    void roundTrip() {
        final String plain = "{\"data\":[1,2,3],\"ok\":true}";
        final String cipher = encrypt(plain);
        assertEquals(plain, AesDecryptor.decrypt(cipher, KEY), "加密后解密应还原原文");
    }

    @Test
    @DisplayName("正常：密文两端空白可容忍")
    void toleratesSurroundingWhitespace() {
        final String cipher = encrypt("{\"a\":1}");
        assertEquals("{\"a\":1}", AesDecryptor.decrypt("  " + cipher + "\n", KEY));
    }

    @Test
    @DisplayName("边界：空输入返回 null")
    void returnsNullForBlankInput() {
        assertNull(AesDecryptor.decrypt(null, KEY));
        assertNull(AesDecryptor.decrypt("", KEY));
        assertNull(AesDecryptor.decrypt("   ", KEY));
        assertNull(AesDecryptor.decrypt("cipher", null), "密钥为空应返回 null");
        assertNull(AesDecryptor.decrypt("cipher", ""), "密钥为空应返回 null");
    }

    @Test
    @DisplayName("边界：非 Base64 输入返回 null")
    void returnsNullForInvalidBase64() {
        assertNull(AesDecryptor.decrypt("!!!not-base64!!!", KEY));
    }

    @Test
    @DisplayName("边界：密钥不匹配的密文返回 null（填充校验失败）")
    void returnsNullForWrongKey() {
        // 用错误密钥加密的密文，解密时 PKCS5 填充校验失败，应返回 null 而非抛异常
        final String wrongKeyCipher = encryptWithKey("{\"a\":1}", "WrongKeyWrongKeyWrongKeyWrongKey");
        assertNull(AesDecryptor.decrypt(wrongKeyCipher, KEY));
    }

    /**
     * 超长填充值（0x15 = 21，超过块大小，模拟超长填充兼容场景）
     */
    private static final int OVERSIZED_PADDING = 0x15;
    /**
     * 篡改测试：翻转距密文末尾的偏移量（落在填充区，破坏填充一致性）
     */
    private static final int TAMPER_OFFSET_FROM_END = 9;

    @Test
    @DisplayName("兼容：超长填充值密文可解密（对齐 CryptoJS 宽松行为）")
    void decryptsOversizedPadding() {
        // 真实场景：尾部填充值 21（0x15）超过 16 块大小，JDK 严格模式拒绝，
        // CryptoJS 宽松剥离；本类应回退 NoPadding 手动剥离成功
        // 明文长度须 %16==11，使"明文 + 21 字节填充"恰好块对齐（NoPadding 加密要求）
        final String plain = "{\"ok\":true}";
        assertEquals(11, plain.length(), "测试前置：明文长度 %16 应等于 11");
        final String cipher = encryptWithOversizedPadding(plain);
        assertEquals(plain, AesDecryptor.decrypt(cipher, KEY),
                "超长填充值密文应走兼容路径并正确剥离");
    }

    @Test
    @DisplayName("兼容：超长填充尾部字节不一致时不误剥（返回 null）")
    void rejectsInconsistentOversizedPadding() {
        // 尾部声明填充 21 但实际字节不全为 0x15：手动剥离校验应拒绝，不误剥真实数据
        final String plain = "{\"ok\":true}";
        final String cipher = encryptWithOversizedPadding(plain);
        // 篡改密文使尾部填充结构非法（翻转落在填充区的某一字节）
        final byte[] raw = Base64.getDecoder().decode(cipher);
        raw[raw.length - TAMPER_OFFSET_FROM_END] ^= 0x01;
        final String tampered = Base64.getEncoder().encodeToString(raw);
        assertNull(AesDecryptor.decrypt(tampered, KEY), "填充结构非法的密文应返回 null");
    }

    /**
     * 用 NoPadding 加密"明文 + 21 字节 0x15"（模拟超长填充密文）。
     *
     * @param plain 明文（长度须满足 {@code len % 16 == 11}，保证总长块对齐）
     * @return Base64 密文
     */
    private static String encryptWithOversizedPadding(final String plain) {
        try {
            final byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
            final byte[] ivBytes = Arrays.copyOf(keyBytes, CBC_BLOCK_SIZE);
            final byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            final byte[] padded = Arrays.copyOf(plainBytes, plainBytes.length + OVERSIZED_PADDING);
            Arrays.fill(padded, plainBytes.length, padded.length, (byte) OVERSIZED_PADDING);
            final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (final Exception e) {
            throw new IllegalStateException("测试加密失败", e);
        }
    }

    /**
     * 用固定密钥加密明文（IV = 密钥前 16 字节）。
     *
     * @param plain 明文
     * @return Base64 密文
     */
    private static String encrypt(final String plain) {
        return encryptWithKey(plain, KEY);
    }

    /**
     * 用指定密钥加密明文（IV = 密钥前 16 字节）。
     *
     * @param plain 明文
     * @param key   密钥
     * @return Base64 密文
     */
    private static String encryptWithKey(final String plain, final String key) {
        try {
            final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            final byte[] ivBytes = Arrays.copyOf(keyBytes, CBC_BLOCK_SIZE);
            final Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("测试加密失败", e);
        }
    }
}
