package com.acme.prism.core.parser;

import com.acme.prism.core.json.JsonRepairer;
import com.acme.prism.core.json.JsonRepairer.FixType;
import com.acme.prism.core.json.JsonRepairer.RepairResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
     * 模拟 MainPanel.resolveJson 的逻辑：PathParser → JwtParser → MongoDB 短路 → AnyParser，返回第一个合法 JSON。
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
        if (JsonRepairer.containsMongoWrapper(text)) {
            return "";
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

    @Test
    @DisplayName("短路：MongoDB 扩展 JSON 包装被 AnyParser 跳过（不转成带引号字符串）")
    void mongoWrapperSkipsAnyParser() {
        // 用户复现场景：粘贴 MongoDB 扩展 JSON 后，YAML 检测会抢先消费成带引号字符串，
        // 破坏修复器输入；应短路跳过自动识别，交给修复器剥离包装
        final String mongo = "{\"id\": ObjectId(\"507f1f77bcf86cd799439011\"), \"n\": NumberLong(123)}";
        // 根因佐证：AnyParser 确实会将该文本当 YAML 消费（非空返回），证明短路必要
        assertFalse(AnyParser.convert(mongo).isEmpty(),
                "根因佐证：无短路时该文本会被 YAML 检测消费（修复前破坏行为）");
        // 修复断言：短路生效，文本不被自动识别消费
        assertTrue(resolveJson(mongo).isEmpty(),
                "含 MongoDB 包装的文本应短路跳过自动识别，避免 YAML 抢先消费");
    }

    @Test
    @DisplayName("端到端：MongoDB 包装文本短路后由修复器正确剥离包装")
    void mongoWrapperShortCircuitedThenRepairable() {
        // 完整链路复现用户场景：粘贴 → 自动识别短路（文本保持原样）→ 修复器剥离包装
        final String mongo = "{\"id\": ObjectId(\"507f1f77bcf86cd799439011\"), \"n\": NumberLong(123)}";
        assertTrue(resolveJson(mongo).isEmpty(), "自动识别应短路，文本保持原样");
        final RepairResult result = JsonRepairer.repair(mongo);
        assertNotNull(result, "修复器应能处理原始 MongoDB 包装文本");
        final JSONObject obj = JSON.parseObject(result.json());
        assertEquals("507f1f77bcf86cd799439011", obj.getString("id"), "ObjectId 应剥离为字符串");
        assertEquals(123, obj.getLongValue("n"), "NumberLong 应剥离为数字");
        assertTrue(result.fixes().contains(FixType.MONGODB), "修复日志应包含 MongoDB 剥离");
    }

    @Test
    @DisplayName("短路：containsMongoWrapper 精确词边界检测")
    void containsMongoWrapperMatchesExactNames() {
        assertTrue(JsonRepairer.containsMongoWrapper("{\"a\": NumberLong(1)}"));
        assertTrue(JsonRepairer.containsMongoWrapper("{\"a\": ISODate(\"2024-01-01\")}"));
        assertTrue(JsonRepairer.containsMongoWrapper("{\"a\": ObjectId(\"x\")}"));
        assertTrue(JsonRepairer.containsMongoWrapper("{\"a\": MinKey}"));
        assertTrue(JsonRepairer.containsMongoWrapper("{\"a\": new Date(123)}"), "new Date 也应短路防 YAML 误判");
        assertFalse(JsonRepairer.containsMongoWrapper("{\"a\": 1}"), "普通 JSON 不应误判");
        assertFalse(JsonRepairer.containsMongoWrapper(""), "空文本不应误判");
    }

    @Test
    @DisplayName("行为等价：合法 JSON 内的包装名文本不受短路影响（AnyParser 本身也跳过合法 JSON）")
    void validJsonWithWrapperNameInStringBehavesEquivalently() {
        // containsMongoWrapper 正则无引号感知，字符串值内的 "NumberLong(1)" 也会命中；
        // 但该文本是合法 JSON，AnyParser 开头就会短路返回空串——两种路径结果等价，无破坏
        final String validJson = "{\"a\": \"NumberLong(1)\"}";
        assertTrue(resolveJson(validJson).isEmpty(), "合法 JSON 应返回空串（短路与否行为一致）");
    }
}
