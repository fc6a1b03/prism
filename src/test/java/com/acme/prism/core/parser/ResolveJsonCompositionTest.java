package com.acme.prism.core.parser;

import com.alibaba.fastjson2.JSON;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * resolveJson 组合逻辑测试：验证 MainPanel.resolveJson 的 PathParser → JwtParser → AnyParser 短路顺序。
 *
 * <p>不依赖 IntelliJ 环境，直接测试三个解析器的组合行为等价于 resolveJson 的真实执行路径。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class ResolveJsonCompositionTest {

    /**
     * 模拟 MainPanel.resolveJson 的逻辑：PathParser → JwtParser → AnyParser，返回第一个合法 JSON。
     */
    private static String resolveJson(final String text) {
        final String pathResult = PathParser.convert(text);
        if (JSON.isValid(pathResult)) {
            return pathResult;
        }
        final String jwtResult = JwtParser.convert(text);
        if (JSON.isValid(jwtResult)) {
            return jwtResult;
        }
        return AnyParser.convert(text);
    }

    // --------------- 短路优先级 ---------------

    @Test
    @DisplayName("短路：合法 JSON 被 AnyParser 跳过（优先级最低），不可达的 PathParser/JwtParser 返回空串，最终返回空串")
    void validJsonSkippedByAnyParser() {
        // 合法 JSON 会被 AnyParser.convert 跳过返回空串
        // PathParser/JwtParser 对非路径/非JWT文本也返回空串
        assertTrue(resolveJson("{\"a\":1}").isEmpty(),
                "合法 JSON 无需转换，三个 parser 都应该返回空串");
    }

    @Test
    @DisplayName("短路：YAML 输入被 AnyParser 识别（PathParser/JwtParser 返回空串后落入 AnyParser）")
    void yamlFallsIntoAnyParser() {
        // YAML 不是路径也不是 JWT，前两个返回空串，由 AnyParser 处理
        final String result = resolveJson("name: test\nage: 18");
        assertAll(
                () -> assertFalse(result.isEmpty(), "YAML 应由 AnyParser 转换为 JSON"),
                () -> assertTrue(JSON.isValid(result)),
                () -> assertTrue(result.contains("name"))
        );
    }

    @Test
    @DisplayName("短路：XML 输入被 AnyParser 识别")
    void xmlFallsIntoAnyParser() {
        final String result = resolveJson("<root><key>value</key></root>");
        assertAll(
                () -> assertFalse(result.isEmpty()),
                () -> assertTrue(JSON.isValid(result)),
                () -> assertTrue(result.contains("value"))
        );
    }

    @Test
    @DisplayName("短路：URL 参数输入被 AnyParser 识别")
    void urlParamsFallsIntoAnyParser() {
        final String result = resolveJson("a=1&b=hello");
        assertAll(
                () -> assertFalse(result.isEmpty()),
                () -> assertTrue(JSON.isValid(result)),
                () -> assertTrue(result.contains("a"))
        );
    }

    @Test
    @DisplayName("短路：BASE64 输入被 AnyParser 识别")
    void base64FallsIntoAnyParser() {
        // "{\"x\":1}" 的 Base64 编码
        final String result = resolveJson("eyJ4IjoxfQ==");
        assertAll(
                () -> assertFalse(result.isEmpty()),
                () -> assertTrue(JSON.isValid(result)),
                () -> assertTrue(result.contains("x"))
        );
    }

    @Test
    @DisplayName("短路：JWT token 被 JwtParser 识别（PathParser 返回空串后落入 JwtParser）")
    void jwtDetectedAfterPathParser() {
        final Algorithm algorithm = Algorithm.HMAC256("test-secret");
        final String jwt = JWT.create()
                .withIssuer("test")
                .withSubject("user1")
                .sign(algorithm);
        final String result = resolveJson(jwt);

        assertAll(
                () -> assertFalse(result.isEmpty(), "JWT 应被 JwtParser 解析"),
                () -> assertTrue(JSON.isValid(result)),
                () -> assertTrue(result.contains("payload"), "JWT 解析结果应包含 payload"),
                () -> assertTrue(result.contains("user1"), "JWT 解析结果应包含 subject")
        );
    }

    // --------------- 各级返回空串正常降级 ---------------

    @Test
    @DisplayName("降级：非路径非 JWT 非结构化文本三级全空，最终返回空串")
    void plainTextReturnsEmptyAfterAllParsers() {
        assertTrue(resolveJson("hello world").isEmpty(),
                "普通文本三级 parser 都返回空串，最终应为空串");
    }

    @Test
    @DisplayName("降级：垃圾文本三级全空，最终返回空串")
    void garbageTextReturnsEmpty() {
        assertTrue(resolveJson("!!!!!!不是任何格式").isEmpty(),
                "垃圾文本应返回空串");
    }

    @Test
    @DisplayName("降级：空文本直接返回空串")
    void emptyTextReturnsEmpty() {
        assertTrue(resolveJson("").isEmpty(), "空文本应返回空串");
    }
}
