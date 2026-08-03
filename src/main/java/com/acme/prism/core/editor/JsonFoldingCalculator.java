package com.acme.prism.core.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 折叠区域计算器：扫描文本，找出跨行的对象/数组括号对。
 *
 * <p>纯逻辑类，不依赖 IntelliJ API，可单元测试。仅返回跨行区间
 * （起始行与结束行不同），单行/压缩 JSON 不产生折叠区域。
 *
 * @author 拒绝者
 * @date 2026-07-31
 */
public class JsonFoldingCalculator {

    /**
     * 折叠区域：startOffset 起、endOffset 止（含两端），placeholder 为折叠占位文本。
     */
    public record FoldRegion(int startOffset, int endOffset, String placeholder) {
    }

    /**
     * 计算文本中可折叠的 JSON 括号对。
     *
     * @param text JSON 文本
     * @return 跨行括号对的折叠区域列表；无折叠点时返回空列表
     */
    public static List<FoldRegion> calculate(final String text) {
        final List<FoldRegion> regions = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return regions;
        }
        // 预计算每行起始 offset，用于 O(log n) 定位任意 offset 所在行
        final int[] lineStarts = buildLineStarts(text);
        scan(text, 0, text.length(), lineStarts, regions);
        return regions;
    }

    private static int[] buildLineStarts(final String text) {
        final List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        final int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            arr[i] = starts.get(i);
        }
        return arr;
    }

    private static void scan(final String text, final int start, final int end,
                             final int[] lineStarts, final List<FoldRegion> regions) {
        int pos = start;
        boolean inString = false;
        while (pos < end) {
            final char c = text.charAt(pos);
            if (c == '"') {
                inString = !inString;
                pos++;
                continue;
            }
            if (inString) {
                pos++;
                continue;
            }
            if (c == '{' || c == '[') {
                final char close = c == '{' ? '}' : ']';
                final int matched = findMatching(text, pos, close);
                if (matched < 0) {
                    return;
                }
                // 仅跨行区间可折叠
                if (lineOf(lineStarts, pos) < lineOf(lineStarts, matched)) {
                    regions.add(new FoldRegion(pos, matched + 1, c == '{' ? "{...}" : "[...]"));
                }
                // 递归处理内部
                scan(text, pos + 1, matched, lineStarts, regions);
                pos = matched + 1;
            } else {
                pos++;
            }
        }
    }

    private static int findMatching(final String text, final int start, final char close) {
        final char open = close == '}' ? '{' : '[';
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static int lineOf(final int[] lineStarts, final int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (lineStarts[mid] <= offset) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return high;
    }
}
