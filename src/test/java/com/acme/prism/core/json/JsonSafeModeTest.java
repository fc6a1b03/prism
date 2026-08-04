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
 * <p>验证 fastjson2 在默认配置与 SafeMode（{@code -Dfastjson2.parser.safeMode=true}）
 * 下的安全行为，守护插件的安全姿态并防止升级引入回归。
 *
 * <p>安全基线（实测于 2.0.62 与 2.0.64，测试任务 JVM 参数开启 SafeMode）：
 * <ul>
 *   <li>默认配置（不传 SupportAutoType，即插件全部实际用法）：@type 仅作普通字段解析，从不实例化类。</li>
 *   <li>SafeMode 开启时，显式传 {@link JSONReader.Feature#SupportAutoType} 同样不实例化 @type
 *       ——结果恒为 {@link JSONObject}。</li>
 * </ul>
 *
 * <p>漏洞背景：2026-07-27 披露 fastjson2 ≤ 2.0.62 的 FNV-1a 哈希碰撞可绕过 AutoType
 * 白名单校验（XVE-2026-42782，CVSS 9.8），已在 2.0.63 修复（白名单 hash 命中后文本回验、
 * URL 特殊字符类型名拒绝、accept 前缀不再覆盖 ClassLoader/DataSource/RowSet 危险基类）。
 * 该漏洞影响的是 AutoType 白名单场景；本插件从不开启 AutoType，叠加 SafeMode 纵深防御，
 * 攻击面极小。升级 2.0.64 除获得该修复外，还附带 JSONB OOM/DoS、Metaspace 泄漏、
 * record 泛型等多项修复。
 *
 * <p>断言说明：fastjson2 的 {@link JSONObject} 继承自 {@link java.util.LinkedHashMap}，
 * 因此 {@code assertInstanceOf(HashMap.class, result)} 恒真、无法区分 JSONObject 与
 * 真正的 @type 实例（旧版测试即因此误报"SafeMode 可被绕过"）。本测试统一断言
 * {@code result.getClass() == JSONObject.class} 精确类型以消除歧义。
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
        assertEquals(JSONObject.class, result.getClass(),
                "默认配置下结果应为 JSONObject，非 @type 指定的类实例");
    }

    @Test
    @DisplayName("安全：SafeMode 下显式 SupportAutoType 也不实例化 @type（2.0.63+ 官方修复后实测）")
    @SuppressWarnings("deprecation")
    void explicitSupportAutoTypeBlockedUnderSafeMode() {
        // 实测（SafeMode JVM 参数开启）：2.0.62 与 2.0.64 下显式传 SupportAutoType，
        // 无论 @type 指向内置安全类（HashMap）还是不存在的类/危险基类（ClassLoader 等），
        // 结果恒为 JSONObject，不触发任何类实例化。
        final String jsonWithType = "{\"@type\":\"java.util.HashMap\",\"key\":\"value\"}";
        final Object result = JSON.parseObject(jsonWithType, Object.class, JSONReader.Feature.SupportAutoType);

        assertNotNull(result);
        assertEquals(JSONObject.class, result.getClass(),
                "SafeMode 下显式 SupportAutoType 不得绕过：结果应为 JSONObject，而非 @type 类实例");
        assertEquals("java.util.HashMap", ((JSONObject) result).getString("@type"),
                "@type 应保留为普通字段");
    }

    @Test
    @DisplayName("安全：SafeMode 下危险类名（不存在的类/危险基类）同样不实例化")
    @SuppressWarnings("deprecation")
    void dangerousTypeNamesNotInstantiatedUnderSafeMode() {
        // 覆盖 XVE-2026-42782 关注面：非白名单/危险基类即使在显式 SupportAutoType 下
        // 也只解析为 JSONObject，类加载路径不被触碰。
        final String[] dangerousPayloads = {
                "{\"@type\":\"com.example.NonExistentEvil\",\"a\":1}",
                "{\"@type\":\"javax.sql.DataSource\",\"a\":1}",
                "{\"@type\":\"java.lang.ClassLoader\",\"a\":1}",
                "{\"@type\":\"java.net.URL\",\"a\":1}"
        };
        for (final String payload : dangerousPayloads) {
            final Object result = JSON.parseObject(payload, Object.class, JSONReader.Feature.SupportAutoType);
            assertEquals(JSONObject.class, result.getClass(),
                    "危险类型名不得实例化: " + payload);
        }
    }

    @Test
    @DisplayName("安全：插件的实际使用场景（JSON.parse + toJSONString）不受 @type 影响")
    void pluginActualUsageIsSafe() {
        // 模拟插件中的实际调用：JSON.parse(json) 和 JSON.toJSONString()
        // 两者都不传 SupportAutoType，因此 @type 不会被解析
        final String maliciousJson = "{\"@type\":\"com.example.Evil\",\"payload\":\"malicious\"}";
        final Object parsed = JSON.parse(maliciousJson);

        assertNotNull(parsed);
        assertEquals(JSONObject.class, parsed.getClass());

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
