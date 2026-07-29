package com.acme.prism.core.parser;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtParser 单元测试：JWT Token 解析及其边界行为。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class JwtParserTest {

    private static final String JWT_SECRET = "prism-test-secret-key-for-jwt";

    /**
     * 创建一个简单的 HS256 JWT。
     *
     * <p>{@link Date} 为 auth0-jwt 库 API 要求，非本代码主动选择旧 API。
     */
    private static String createTestJwt(final String subject, final String key, final String value) {
        final Algorithm algorithm = Algorithm.HMAC256(JWT_SECRET);
        return JWT.create()
                .withIssuer("prism-test")
                .withSubject(subject)
                .withIssuedAt(new Date()) // auth0-jwt 强制要求 Date 类型，非本代码主动使用旧 API
                .withClaim(key, value)
                .sign(algorithm);
    }

    // --------------- convert 边界 ---------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("边界：null / 空串 / 纯空白返回空串")
    void convertReturnsEmptyForBlankInput(final String input) {
        assertTrue(JwtParser.convert(input).isEmpty(), "空白输入应返回空串");
    }

    @Test
    @DisplayName("边界：非 JWT 格式文本返回空串")
    void convertReturnsEmptyForNonJwtText() {
        assertTrue(JwtParser.convert("not a jwt token").isEmpty(), "非 JWT 文本应返回空串");
    }

    @Test
    @DisplayName("边界：格式类似 JWT 但内容为非法的 token 返回空串")
    void convertReturnsEmptyForMalformedJwt() {
        // 三段 Base64 但内容不是合法 JWT
        final String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        final String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("garbage".getBytes(StandardCharsets.UTF_8));
        assertTrue(JwtParser.convert(header + "." + payload + ".signature").isEmpty(),
                "内容非 JSON 的 JWT 格式文本应返回空串");
    }

    // --------------- 合法 JWT 解析 ---------------

    @Test
    @DisplayName("正常：合法 JWT 解析为结构化 JSON（含 token/signature/header/payload 四个字段）")
    void convertsValidJwtToStructuredJson() {
        final String jwt = createTestJwt("user123", "role", "admin");
        final String result = JwtParser.convert(jwt);

        assertAll(
                () -> assertFalse(result.isEmpty(), "合法 JWT 应产生非空结果"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON")
        );

        final JSONObject obj = JSON.parseObject(result);
        assertAll(
                () -> assertTrue(obj.containsKey("token"), "结果应包含 token 字段"),
                () -> assertTrue(obj.containsKey("signature"), "结果应包含 signature 字段"),
                () -> assertTrue(obj.containsKey("header"), "结果应包含 header 字段"),
                () -> assertTrue(obj.containsKey("payload"), "结果应包含 payload 字段")
        );

        // header 内应包含 alg
        final JSONObject header = obj.getJSONObject("header");
        assertNotNull(header, "header 不应为 null");
        assertEquals("HS256", header.getString("alg"), "header 中 alg 应为 HS256");

        // payload 内应包含原始 claims
        final JSONObject payload = obj.getJSONObject("payload");
        assertNotNull(payload, "payload 不应为 null");
        assertEquals("user123", payload.getString("sub"), "payload 中 sub 应为 user123");
        assertEquals("admin", payload.getString("role"), "payload 中 role 应为 admin");
    }

    @Test
    @DisplayName("正常：不含额外 claims 的 JWT 仍正确解析")
    void convertsMinimalJwt() {
        final Algorithm algorithm = Algorithm.HMAC256(JWT_SECRET);
        final String jwt = JWT.create()
                .withIssuer("prism")
                .sign(algorithm);
        final String result = JwtParser.convert(jwt);

        assertTrue(JSON.isValid(result), "最小 JWT 解析结果应为合法 JSON");
        final JSONObject obj = JSON.parseObject(result);
        assertNotNull(obj.getJSONObject("header"), "header 应存在");
        assertNotNull(obj.getJSONObject("payload"), "payload 应存在");
        assertEquals("prism", obj.getJSONObject("payload").getString("iss"), "payload 中 iss 应为 prism");
    }
}
