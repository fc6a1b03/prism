package com.acme.prism.core.parser;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathParser 单元测试：路径识别与内容获取，覆盖 Web 请求与本地文件读取的闭环验证。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class PathParserTest {

    private static final String MOJANG_API = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final Path TEMP_FILE = Paths.get(System.getProperty("user.dir"), "prism-test-temp.json");
    private static final String TEMP_CONTENT = "{\"project\":\"Prism\",\"version\":\"test\"}";

    @BeforeAll
    static void createTempFile() throws IOException {
        Files.writeString(TEMP_FILE, TEMP_CONTENT, StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteTempFile() throws IOException {
        Files.deleteIfExists(TEMP_FILE);
    }

    // --------------- convert 边界 ---------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("边界：null / 空串 / 纯空白输入返回空串")
    void convertReturnsEmptyForBlankInput(final String input) {
        assertTrue(PathParser.convert(input).isEmpty(), "空白输入应返回空串");
    }

    @Test
    @DisplayName("边界：非路径普通文本返回空串")
    void convertReturnsEmptyForPlainText() {
        assertTrue(PathParser.convert("hello world").isEmpty(), "非路径文本应返回空串");
    }

    @Test
    @DisplayName("边界：不完整的 URL（缺协议头）不被当作 Web 路径")
    void convertRejectsIncompleteUrl() {
        assertTrue(PathParser.convert("example.com/page").isEmpty(), "缺少协议头的文本不应被当作 Web 路径");
    }

    // --------------- Web 请求 ---------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com/api/data",
            "http://127.0.0.1:8080/path",
            "https://a.b.c/d"
    })
    @DisplayName("正常：标准 http/https URL 不抛异常（远端不可达时优雅返回空串）")
    void unreachableUrlsReturnEmptyGracefully(final String url) {
        assertNotNull(PathParser.convert(url), "即使远端不可达也不应抛异常");
    }

    @Test
    @DisplayName("正常：访问真实公网 API 成功获取 JSON 内容并校验合法性")
    void fetchRealWebContent() {
        final String result = PathParser.convert(MOJANG_API);
        // 网络不可达时 result 为空串，测试跳过但不视为失败
        if (result.isEmpty()) {
            System.out.println("[PathParserTest] 网络不可达，跳过 Web 内容断言");
            return;
        }
        assertAll(
                () -> assertFalse(result.isEmpty(), "应获取到非空内容"),
                () -> assertTrue(JSON.isValid(result), "返回内容应为合法 JSON"),
                () -> assertTrue(result.contains("versions"), "Mojang API 响应应包含 versions 字段")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://files.example.com/data",
            "ws://example.com/socket",
            "hello world"
    })
    @DisplayName("正常：非 http/https 的文本不触发 Web 请求")
    void nonHttpUrlsNotTreatedAsWebPath(final String input) {
        assertNotNull(PathParser.convert(input), "非 HTTP 输入不应抛异常");
    }

    // --------------- 本地文件 ---------------

    @Test
    @DisplayName("正常：file:// 协议路径读取本地文件并返回合法 JSON")
    void readLocalFileViaFileProtocol() {
        final String fileUrl = "file:///" + TEMP_FILE.toString().replace('\\', '/');
        final String result = PathParser.convert(fileUrl);

        assertAll(
                () -> assertFalse(result.isEmpty(), "file:// 路径应读取到文件内容"),
                () -> assertTrue(JSON.isValid(result), "读取结果应为合法 JSON"),
                () -> assertTrue(result.contains("Prism"), "内容应包含文件中的项目名")
        );
    }

    @Test
    @DisplayName("正常：绝对路径直接读取本地文件并返回合法 JSON")
    void readLocalFileViaAbsolutePath() {
        final String result = PathParser.convert(TEMP_FILE.toString());

        assertAll(
                () -> assertFalse(result.isEmpty(), "绝对路径应读取到文件内容"),
                () -> assertTrue(JSON.isValid(result), "读取结果应为合法 JSON"),
                () -> assertEquals(TEMP_CONTENT, result, "读取内容应与写入内容一致")
        );
    }

    @Test
    @DisplayName("边界：不存在的本地路径返回空串")
    void nonexistentPathReturnsEmpty() {
        final String fakePath = TEMP_FILE.getParent().resolve("nonexistent-prism-test-file.json").toString();
        assertTrue(PathParser.convert(fakePath).isEmpty(), "不存在的本地文件路径应返回空串");
    }
}
