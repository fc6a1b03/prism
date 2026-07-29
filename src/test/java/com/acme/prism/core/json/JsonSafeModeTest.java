package com.acme.prism.core.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fastjson2 安全性验证测试。
 *
 * <p>验证 fastjson2 2.0.62 在默认配置下的安全行为，并记录当前 SafeMode JVM 参数
 *（{@code -Dfastjson2.parser.safeMode=true}）的实际效果差异，供升级后回归对比。
 *
 * <p>背景：2026-07-27 披露 fastjson2 ≤ 2.0.62 的 FNV-1a 哈希碰撞可绕过 AutoType 校验
 *（XVE-2026-42782，CVSS 9.8）。本插件仅做纯数据层面的 JSON 解析/格式化，不涉及
 * Polymorphic 类型反序列化，因此攻击面极小。通过 Gradle test/runIde 配置
 * {@code -Dfastjson2.parser.safeMode=true} 作为纵深防御，并等待官方修复版本。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class JsonSafeModeTest {

    @Test
    @DisplayName("默认安全：含 @type 的 JSON 在 fastjson2 默认关闭 AutoType 时不触发类实例化")
    void defaultConfigIgnoresAutoType() {
        // fastjson2 默认 AutoType 关闭，@type 仅作为普通字段解析（区别于 fastjson 1.x 默认开启）
        final String jsonWithType = "{\"@type\":\"java.util.HashMap\",\"key\":\"value\"}";
        final JSONObject result = JSON.parseObject(jsonWithType);

        assertNotNull(result);
        assertEquals("java.util.HashMap", result.getString("@type"),
                "默认配置下 @type 应被当作普通字符串字段");
        assertEquals("value", result.getString("key"));
        assertInstanceOf(JSONObject.class, result, "默认配置下结果应为 JSONObject，非 @type 指定的类实例");
    }

    @Test
    @DisplayName("已知缺陷（2.0.62）：显式 SupportAutoType 在 SafeMode 下仍可解析 @type —— 等待官方修复")
    @SuppressWarnings("deprecation")
    void explicitAutoTypeStillWorksUnderSafeMode_knownIssue() {
        // 记录 fastjson2 2.0.62 的实际行为作为基线：
        // 即使设置了 -Dfastjson2.parser.safeMode=true，显式传 SupportAutoType 仍会绕过。
        // 升级到修复版本后，此测试需要改为断言 @type 被阻止。
        final String jsonWithType = "{\"@type\":\"java.util.HashMap\",\"key\":\"value\"}";
        final Object result = JSON.parseObject(jsonWithType, Object.class, JSONReader.Feature.SupportAutoType);

        assertNotNull(result);
        // 2.0.62 的行为：SafeMode JVM 参数不能阻止显式 SupportAutoType
        // 修复版本发布后应改为 assertInstanceOf(JSONObject.class, result)
        assertInstanceOf(java.util.HashMap.class, result,
                "2.0.62 已知缺陷：SafeMode 无法阻止显式 SupportAutoType。"
                        + "升级后此断言应改为 JSONObject.class");
    }

    @Test
    @DisplayName("安全：插件的实际使用场景（JSON.parse + toJSONString）不受 @type 影响")
    void pluginActualUsageIsSafe() {
        // 模拟插件中的实际调用：JSON.parse(json) 和 JSON.toJSONString()
        // 两者都不传 SupportAutoType，因此 @type 不会被解析
        final String maliciousJson = "{\"@type\":\"com.example.Evil\",\"payload\":\"malicious\"}";
        final Object parsed = JSON.parse(maliciousJson);

        assertNotNull(parsed);
        assertInstanceOf(JSONObject.class, parsed);

        // 序列化也正常
        final String output = JSON.toJSONString(parsed);
        assertNotNull(output);
        assertTrue(output.contains("malicious"));
    }

    @Test
    @DisplayName("安全：普通 JSON 解析和序列化不受任何影响")
    void normalOperationsUnaffected() {
        final String normalJson = "{\"name\":\"test\",\"value\":42,\"items\":[1,2,3]}";
        final JSONObject parsed = JSON.parseObject(normalJson);

        assertAll(
                () -> assertEquals("test", parsed.getString("name")),
                () -> assertEquals(42, parsed.getInteger("value")),
                () -> assertNotNull(parsed.getJSONArray("items"))
        );

        final String formatted = JSON.toJSONString(parsed);
        assertTrue(formatted.contains("\"name\""));
        assertTrue(formatted.contains("\"value\""));
    }
}
