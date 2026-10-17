package io.github.numai5.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从 Markdown 原文中提取 LaTeX 数学公式，并替换为占位符。
 *
 * 为什么要先替换：公式文本里含有 * _ \ [ ] 等字符，如果直接参与后续 Markdown 处理，
 * 会被加粗 / 斜体 / 链接等规则互相破坏。所以先把公式整段摘出来，替换成不含任何
 * Markdown 语法的占位符（\u0001 + 序号 + \u0001），等 Markdown 组装完成后再把
 * 占位符替换为渲染好的公式图片。
 *
 * 支持的定界符：
 *   - $$...$$（块级显示，可跨行）
 *   - $...$（行内，单行；按 Pandoc 规则避免把「$5 和 $10」这类金额误判成公式）
 *   - \(...\)（行内）、\[...\]（块级）
 * 围栏代码块（```）与行内代码（` 或 ``）内部一律不处理。
 *
 * 纯 Java 实现（不依赖 Android），便于沙箱单元测试。
 */
public final class MathExtractor {

    /** 单条消息最多提取的公式数（防异常内容拖垮界面） */
    private static final int MAX_SEGMENTS = 150;
    /** 单个公式最大字符数，超出则保持原文 */
    private static final int MAX_LATEX_LENGTH = 2000;
    /** 占位符控制字符（正常文本不会出现） */
    private static final char PLACEHOLDER = '\u0001';

    /** 一段公式 */
    public static final class Segment {
        /** 公式内容（不含定界符，已 trim） */
        public final String latex;
        /** 原始文本（含定界符），渲染失败时按原文回退显示 */
        public final String raw;
        /** true = $$ / \[ \]（块级显示）；false = $ / \( \)（行内） */
        public final boolean display;
        /** 在原文本中的起点（含定界符） */
        public final int start;
        /** 在原文本中的终点（不含，紧接闭合定界符之后） */
        public final int end;

        Segment(String latex, String raw, boolean display, int start, int end) {
            this.latex = latex;
            this.raw = raw;
            this.display = display;
            this.start = start;
            this.end = end;
        }
    }

    /** 提取结果：text 为替换后的文本，segments 按出现顺序对应占位符编号 */
    public static final class Result {
        public final String text;
        public final List<Segment> segments;

        Result(String text, List<Segment> segments) {
            this.text = text;
            this.segments = segments;
        }

        public boolean hasMath() {
            return !segments.isEmpty();
        }
    }

    private MathExtractor() {
    }

    /** 编号对应的占位符字符串 */
    public static String placeholder(int index) {
        return PLACEHOLDER + String.valueOf(index) + PLACEHOLDER;
    }

    /** 提取公式；没有公式时原样返回（segments 为空列表） */
    public static Result extract(String source) {
        if (source == null || source.length() == 0) {
            return new Result(source == null ? "" : source, Collections.<Segment>emptyList());
        }
        if (source.indexOf('$') < 0 && source.indexOf("\\(") < 0 && source.indexOf("\\[") < 0) {
            return new Result(source, Collections.<Segment>emptyList());
        }

        int n = source.length();
        StringBuilder out = new StringBuilder(n + 16);
        List<Segment> segments = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 围栏代码块：``` 在行首时切换状态，围栏行原样保留（与 MarkdownParser 的处理一致）
            if (isLineStart(source, i) && regionStartsWith(source, i, "```")) {
                inFence = !inFence;
                int eol = source.indexOf('\n', i);
                int end = eol < 0 ? n : eol;
                out.append(source, i, end);
                i = end;
                continue;
            }
            if (inFence) {
                out.append(c);
                i++;
                continue;
            }

            // 行内代码：原样跳过（` 或 `` 起止）
            if (c == '`') {
                int runEnd = i;
                while (runEnd < n && source.charAt(runEnd) == '`') runEnd++;
                int runLen = runEnd - i;
                int close = findCodeEnd(source, runEnd, runLen);
                if (close >= 0) {
                    out.append(source, i, close + runLen);
                    i = close + runLen;
                } else {
                    out.append(source, i, runEnd);
                    i = runEnd;
                }
                continue;
            }

            if (c == '\\' && i + 1 < n) {
                char delim = source.charAt(i + 1);
                if (delim == '(' || delim == '[') {
                    String closeToken = delim == '(' ? "\\)" : "\\]";
                    int close = source.indexOf(closeToken, i + 2);
                    if (close > i + 1) {
                        String latex = source.substring(i + 2, close).trim();
                        if (isRenderable(latex)) {
                            addSegment(out, segments, latex, source.substring(i, close + 2), delim == '[', i, close + 2);
                            i = close + 2;
                            continue;
                        }
                    }
                } else if (delim == '$') {
                    // 转义美元符：原样保留，且不作为公式起点
                    out.append(c).append(delim);
                    i += 2;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == '$') {
                int consumed = tryExtractDollar(source, i, out, segments);
                if (consumed > i) {
                    i = consumed;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
        return new Result(out.toString(), segments);
    }

    /** 处理 $ / $$ 开头的公式；成功返回消费到的位置，失败返回 start */
    private static int tryExtractDollar(String s, int start, StringBuilder out, List<Segment> segments) {
        int n = s.length();
        if (start + 1 < n && s.charAt(start + 1) == '$') {
            int close = s.indexOf("$$", start + 2);
            if (close > start + 1) {
                String latex = s.substring(start + 2, close).trim();
                if (isRenderable(latex)) {
                    addSegment(out, segments, latex, s.substring(start, close + 2), true, start, close + 2);
                    return close + 2;
                }
            }
            return start;
        }
        int close = findInlineDollarEnd(s, start);
        if (close > 0) {
            String latex = s.substring(start + 1, close).trim();
            if (isRenderable(latex)) {
                addSegment(out, segments, latex, s.substring(start, close + 1), false, start, close + 1);
                return close + 1;
            }
        }
        return start;
    }

    /**
     * 行内 $ 的闭合位置（Pandoc 规则）：
     *   - 左 $ 后必须是非空白且非 $；
     *   - 右 $ 前必须是非空白；右 $ 后不能紧跟数字（避免「$5 和 $10」被误判）；
     *   - 行内公式不跨行。
     * 返回 -1 表示不是公式，保持原文。
     */
    private static int findInlineDollarEnd(String s, int open) {
        int n = s.length();
        if (open + 1 >= n) return -1;
        char next = s.charAt(open + 1);
        if (Character.isWhitespace(next) || next == '$') return -1;
        for (int j = open + 1; j < n; j++) {
            char c = s.charAt(j);
            if (c == '\n') return -1;
            if (c == '$' && j > open + 1) {
                char prev = s.charAt(j - 1);
                boolean prevOk = !Character.isWhitespace(prev);
                boolean nextOk = (j + 1 >= n) || !Character.isDigit(s.charAt(j + 1));
                if (prevOk && nextOk) return j;
            }
        }
        return -1;
    }

    private static void addSegment(StringBuilder out, List<Segment> segments,
                                   String latex, String raw, boolean display, int start, int end) {
        if (segments.size() >= MAX_SEGMENTS) {
            out.append(raw);
            return;
        }
        out.append(placeholder(segments.size()));
        segments.add(new Segment(latex, raw, display, start, end));
    }

    private static boolean isRenderable(String latex) {
        return latex.length() > 0 && latex.length() <= MAX_LATEX_LENGTH;
    }

    /** 找长度为 runLen 的反引号闭合段；限定同一段落内（不跨空行），找不到返回 -1 */
    private static int findCodeEnd(String s, int from, int runLen) {
        int n = s.length();
        int paraEnd = s.indexOf("\n\n", from);
        int limit = paraEnd < 0 ? n : paraEnd + 1;
        int j = from;
        while (j + runLen <= limit) {
            if (s.charAt(j) == '`') {
                int k = j;
                while (k < limit && s.charAt(k) == '`') k++;
                if (k - j == runLen) return j;
                j = k;
            } else {
                j++;
            }
        }
        return -1;
    }

    private static boolean isLineStart(String s, int i) {
        return i == 0 || s.charAt(i - 1) == '\n';
    }

    private static boolean regionStartsWith(String s, int i, String prefix) {
        return i + prefix.length() <= s.length() && s.startsWith(prefix, i);
    }
}
