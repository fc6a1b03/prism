package com.acme.prism.core.editor.record;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑器状态（EditorState）编解码单元测试
 *
 * @author 拒绝者
 * @date 2026-07-19
 */
class EditorStateTest {

    @Test
    @DisplayName("正常：单条记录编码解码往返一致")
    void roundTripsSingleRecord() {
        final EditorState state = new EditorState(1, "{\"a\":1}");
        final List<EditorState> decoded = EditorState.decode(EditorState.encode(List.of(state)));
        assertAll(
                () -> assertEquals(1, decoded.size(), "往返后记录数应一致"),
                () -> assertEquals(state, decoded.getFirst(), "往返后记录应相等")
        );
    }

    @Test
    @DisplayName("正常：多条记录编码解码往返一致且保持顺序")
    void roundTripsMultipleRecords() {
        final List<EditorState> states = List.of(
                new EditorState(1, "first"),
                new EditorState(2, "{\"b\":2}"),
                new EditorState(3, "third line\nsecond line")
        );
        assertEquals(states, EditorState.decode(EditorState.encode(states)), "多条记录往返后应完全相等");
    }

    @Test
    @DisplayName("正常：中文与特殊字符内容经 Base64 往返无损")
    void roundTripsUnicodeContent() {
        final EditorState state = new EditorState(7, "中文内容🙂emoji\t制表符&=分隔符样式");
        final List<EditorState> decoded = EditorState.decode(EditorState.encode(List.of(state)));
        assertAll(
                () -> assertEquals(1, decoded.size(), "往返后记录数应一致"),
                () -> assertEquals(state, decoded.getFirst(), "中文与特殊字符内容应无损往返")
        );
    }

    @Test
    @DisplayName("正常：内容包含分隔符形态字符时经 Base64 往返无损")
    void roundTripsContentContainingSeparators() {
        final EditorState state = new EditorState(9, "含外层\u001E与内部\u001F分隔符的内容");
        final List<EditorState> decoded = EditorState.decode(EditorState.encode(List.of(state)));
        assertAll(
                () -> assertEquals(1, decoded.size(), "Base64 应屏蔽内容中的分隔符字符"),
                () -> assertEquals(state, decoded.getFirst(), "含分隔符形态字符的内容应无损往返")
        );
    }

    @Test
    @DisplayName("边界：encode 对 null 与空列表返回空串")
    void encodesNullAndEmptyListToEmpty() {
        assertAll(
                () -> assertEquals("", EditorState.encode(null), "null 列表应编码为空串"),
                () -> assertEquals("", EditorState.encode(List.of()), "空列表应编码为空串")
        );
    }

    @Test
    @DisplayName("边界：encode 对 null 内容按空串处理且不抛异常")
    void encodeToleratesNullContent() {
        final String encoded = assertDoesNotThrow(
                () -> EditorState.encode(List.of(new EditorState(2, null))),
                "null 内容不应抛异常"
        );
        final List<EditorState> decoded = EditorState.decode(encoded);
        assertAll(
                () -> assertEquals(1, decoded.size(), "记录应可往返"),
                () -> assertEquals("", decoded.getFirst().content(), "null 内容应归一为空串")
        );
    }

    @Test
    @DisplayName("边界：encode 跳过列表中的 null 元素")
    void encodeSkipsNullElements() {
        final String encoded = EditorState.encode(java.util.Arrays.asList(new EditorState(1, "x"), null));
        final List<EditorState> decoded = EditorState.decode(encoded);
        assertAll(
                () -> assertEquals(1, decoded.size(), "null 元素应被跳过"),
                () -> assertEquals(new EditorState(1, "x"), decoded.getFirst(), "非 null 元素应正常往返")
        );
    }

    @Test
    @DisplayName("边界：decode 对 null 与空串返回空列表")
    void decodesNullAndEmptyToEmptyList() {
        assertAll(
                () -> assertTrue(EditorState.decode(null).isEmpty(), "null 输入应解码为空列表"),
                () -> assertTrue(EditorState.decode("").isEmpty(), "空串输入应解码为空列表")
        );
    }

    @Test
    @DisplayName("边界：decode 对垃圾输入返回空列表")
    void decodesGarbageToEmptyList() {
        assertTrue(EditorState.decode("垃圾文本没有分隔符").isEmpty(), "无分隔符的垃圾输入应解码为空列表");
    }

    @Test
    @DisplayName("边界：decode 跳过缺少内部分隔符的缺段记录")
    void decodeSkipsIncompleteRecords() {
        // 无内部分隔符的单段记录属于缺段，应被跳过
        assertTrue(EditorState.decode("1").isEmpty(), "缺段记录应被跳过");
    }

    @Test
    @DisplayName("正常：带滚动位置的记录编码解码往返一致")
    void roundTripsWithScrollOffset() {
        final EditorState state = new EditorState(5, "{\"a\":1}", 1234);
        final List<EditorState> decoded = EditorState.decode(EditorState.encode(List.of(state)));
        assertAll(
                () -> assertEquals(1, decoded.size()),
                () -> assertEquals(1234, decoded.getFirst().scrollOffset(), "滚动位置应无损往返")
        );
    }

    @Test
    @DisplayName("兼容：旧格式（无滚动位置段）解码后 scrollOffset 为 null")
    void decodesLegacyTwoSegmentFormat() {
        // 旧版本只存 id + content 两段，无第三段滚动位置
        final String legacy = "1\u001FeyJhIjoxfQ==";
        final List<EditorState> decoded = EditorState.decode(legacy);
        assertAll(
                () -> assertEquals(1, decoded.size()),
                () -> assertEquals(1, decoded.getFirst().editorId()),
                () -> assertEquals("{\"a\":1}", decoded.getFirst().content()),
                () -> assertNull(decoded.getFirst().scrollOffset(), "旧数据滚动位置应为 null，恢复时默认置顶")
        );
    }

    @Test
    @DisplayName("兼容：三参 null 滚动位置与双参构造等价")
    void nullScrollOffsetEquivalent() {
        final EditorState withNull = new EditorState(1, "x", null);
        final EditorState legacy = new EditorState(1, "x");
        assertEquals(legacy, withNull, "null 滚动位置应与双参构造等价");
        assertEquals(legacy, EditorState.decode(EditorState.encode(List.of(withNull))).getFirst(),
                "null 滚动位置编码解码后应保持 null");
    }

    @Test
    @DisplayName("正常：带光标位置的记录编码解码往返一致")
    void roundTripsWithCaretOffset() {
        final EditorState state = new EditorState(6, "{\"a\":1}", null, 42);
        final List<EditorState> decoded = EditorState.decode(EditorState.encode(List.of(state)));
        assertAll(
                () -> assertEquals(1, decoded.size()),
                () -> assertEquals(42, decoded.getFirst().caretOffset(), "光标位置应无损往返")
        );
    }

    @Test
    @DisplayName("兼容：旧格式（无光标段）解码后 caretOffset 为 null")
    void decodesLegacyFormatCaretNull() {
        // 旧版本只存 id + content 两段
        final List<EditorState> decoded = EditorState.decode("1\u001FeyJhIjoxfQ==");
        assertAll(
                () -> assertEquals(1, decoded.size()),
                () -> assertNull(decoded.getFirst().caretOffset(), "旧数据光标位置应为 null")
        );
    }

    @Test
    @DisplayName("边界：decode 对非数字编辑器 ID 容错为 null")
    void decodeToleratesNonNumericEditorId() {
        final List<EditorState> decoded = EditorState.decode("abc\u001FaGVsbG8=");
        assertAll(
                () -> assertEquals(1, decoded.size(), "合法 Base64 段应被解码"),
                () -> assertNull(decoded.getFirst().editorId(), "非数字 ID 应容错为 null"),
                () -> assertEquals("hello", decoded.getFirst().content(), "Base64 内容应正确解码")
        );
    }

    @Test
    @DisplayName("边界：空内容记录编码后可完整往返（split 保留尾部空段）")
    void decodePreservesEmptyContentRecord() {
        final String encoded = EditorState.encode(List.of(new EditorState(1, "")));
        final List<EditorState> decoded = EditorState.decode(encoded);
        assertAll(
                () -> assertEquals(1, decoded.size(), "空内容记录应可往返保留"),
                () -> assertEquals(1, decoded.getFirst().editorId(), "编辑器 ID 应一致"),
                () -> assertEquals("", decoded.getFirst().content(), "空内容应一致")
        );
    }

    @Test
    @DisplayName("异常：decode 对非法 Base64 内容抛 IllegalArgumentException")
    void decodeThrowsForInvalidBase64() {
        assertThrows(IllegalArgumentException.class,
                () -> EditorState.decode("1\u001F@@@"),
                "非法 Base64 内容应抛 IllegalArgumentException");
    }
}
