package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简化版 Markdown → Spannable（步骤18 重写）。
 *
 * 旧实现的问题：applyInline/applyPattern/applyLinkPattern 每次都 clear()+重建，
 * 会清掉此前设置的所有 span（标题/引用/列表样式全部丢失，最终只剩最后一轮格式）；
 * CODE_BLOCK 正则定义了却从未使用。
 *
 * 新实现：文本加工（去标记）只在普通字符串上进行，所有样式先记录为
 * (start, end, span) 规格；每次替换文本时按映射函数同步更新已有规格坐标；
 * 最终一次性在同一个 SpannableStringBuilder 上应用全部 span，不再有清空重建。
 * 处理顺序：围栏代码块（块级，内容不参与 inline）→ 行内代码（加保护标记）→
 * 粗体 → 斜体 → 删除线 → 链接；被代码标记完整覆盖的匹配一律跳过。
 * parse 本身不做缓存；流式生成时调用方每次传入最新全文即可（Message 侧已有失效机制）。
 */
public class MarkdownParser {

    /** 代码区域标记：不渲染，只用于让后续 inline 规则跳过代码内容 */
    private static final class CodeSpan {
    }

    /** 待应用的 span 规格（坐标基于当前纯文本） */
    private static final class SpanSpec {
        int start;
        int end;
        final Object span;

        SpanSpec(int start, int end, Object span) {
            this.start = start;
            this.end = end;
            this.span = span;
        }
    }

    /** 一次正则匹配（含去标记后内容的坐标） */
    private static final class Match {
        int start;
        int end;
        int innerStart;
        int innerEnd;
        String extra;
    }

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("_([^_]+)_");
    private static final Pattern STRIKE = Pattern.compile("~~([^~]+)~~");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");

    public static CharSequence parse(Context context, String text, boolean isGenerating) {
        if (text == null || text.length() == 0) return "";
        StringBuilder work = new StringBuilder();
        List<SpanSpec> specs = new ArrayList<>();
        appendBlocks(work, specs, text);
        applyInlines(work, specs);
        return assemble(work, specs);
    }

    // ===== 块级：围栏代码块 / 标题 / 引用 / 列表 =====

    private static void appendBlocks(StringBuilder sb, List<SpanSpec> specs, String text) {
        String[] lines = text.split("\n");
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                inCode = !inCode; // 围栏行本身不渲染
                continue;
            }
            int lineStart = sb.length();
            if (inCode) {
                sb.append(line);
                specs.add(new SpanSpec(lineStart, sb.length(), new CodeSpan()));
            } else if (line.startsWith("# ")) {
                sb.append(line.substring(2));
                addHeading(specs, lineStart, sb.length(), 1.4f);
            } else if (line.startsWith("## ")) {
                sb.append(line.substring(3));
                addHeading(specs, lineStart, sb.length(), 1.2f);
            } else if (line.startsWith("### ")) {
                sb.append(line.substring(4));
                addHeading(specs, lineStart, sb.length(), 1.2f);
            } else if (line.startsWith("> ")) {
                sb.append(line.substring(2));
                specs.add(new SpanSpec(lineStart, sb.length(), new QuoteSpan()));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                sb.append(line.substring(2));
                specs.add(new SpanSpec(lineStart, sb.length(), new BulletSpan(40)));
            } else {
                sb.append(line);
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        // 收尾可能因隐藏的围栏行留下多余换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
    }

    private static void addHeading(List<SpanSpec> specs, int start, int end, float scale) {
        if (end <= start) return;
        specs.add(new SpanSpec(start, end, new StyleSpan(Typeface.BOLD)));
        specs.add(new SpanSpec(start, end, new RelativeSizeSpan(scale)));
    }

    // ===== 行内：代码 / 粗体 / 斜体 / 删除线 / 链接 =====

    private static void applyInlines(StringBuilder sb, List<SpanSpec> specs) {
        applyPattern(sb, specs, INLINE_CODE, new TypefaceSpan("monospace"), true);
        applyPattern(sb, specs, BOLD, new StyleSpan(Typeface.BOLD), false);
        applyPattern(sb, specs, ITALIC_STAR, new StyleSpan(Typeface.ITALIC), false);
        applyPattern(sb, specs, ITALIC_UNDERSCORE, new StyleSpan(Typeface.ITALIC), false);
        applyPattern(sb, specs, STRIKE, new StrikethroughSpan(), false);
        applyLinks(sb, specs);
    }

    private static void applyPattern(StringBuilder text, List<SpanSpec> specs, Pattern pattern,
                                     Object span, boolean protectCode) {
        List<Match> matches = collectMatches(text, specs, pattern);
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            int innerLen = match.innerEnd - match.innerStart;
            replaceRange(text, specs, match.start, match.end, match.innerStart, match.innerEnd);
            int s = match.start;
            int e = s + innerLen;
            if (e > s) {
                specs.add(new SpanSpec(s, e, span));
                if (protectCode) {
                    specs.add(new SpanSpec(s, e, new CodeSpan()));
                }
            }
        }
    }

    private static void applyLinks(StringBuilder text, List<SpanSpec> specs) {
        List<Match> matches = new ArrayList<>();
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            if (insideCode(specs, m.start(), m.end())) continue;
            Match match = new Match();
            match.start = m.start();
            match.end = m.end();
            match.innerStart = m.start(1);
            match.innerEnd = m.end(1);
            match.extra = m.group(2);
            matches.add(match);
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            int innerLen = match.innerEnd - match.innerStart;
            replaceRange(text, specs, match.start, match.end, match.innerStart, match.innerEnd);
            if (innerLen > 0) {
                specs.add(new SpanSpec(match.start, match.start + innerLen, new URLSpan(match.extra)));
            }
        }
    }

    private static List<Match> collectMatches(StringBuilder text, List<SpanSpec> specs, Pattern pattern) {
        List<Match> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (insideCode(specs, m.start(), m.end())) continue;
            Match match = new Match();
            match.start = m.start();
            match.end = m.end();
            match.innerStart = m.start(1);
            match.innerEnd = m.end(1);
            out.add(match);
        }
        return out;
    }

    /** 匹配是否被某个代码标记完整覆盖（是则跳过该匹配，不对代码内容应用 inline 格式） */
    private static boolean insideCode(List<SpanSpec> specs, int start, int end) {
        for (SpanSpec spec : specs) {
            if (spec.span instanceof CodeSpan && spec.start <= start && end <= spec.end) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用 inner 内容替换 [delStart, delEnd)，并同步映射已有规格的坐标：
     * - 编辑区之前：不变；编辑区之后：整体位移 delta；
     * - 与保留内容（inner 区）相交：映射到新坐标，超出部分收拢到边界；
     * - 完全落入被删除部分的规格：收拢为零长度后丢弃。
     */
    private static void replaceRange(StringBuilder text, List<SpanSpec> specs,
                                     int delStart, int delEnd, int innerStart, int innerEnd) {
        String inner = text.substring(innerStart, innerEnd);
        text.replace(delStart, delEnd, inner);
        int delta = inner.length() - (delEnd - delStart);
        Iterator<SpanSpec> it = specs.iterator();
        while (it.hasNext()) {
            SpanSpec spec = it.next();
            int ns = mapPoint(spec.start, delStart, delEnd, innerStart, innerEnd, delta);
            int ne = mapPoint(spec.end, delStart, delEnd, innerStart, innerEnd, delta);
            if (ne <= ns) {
                it.remove();
            } else {
                spec.start = ns;
                spec.end = ne;
            }
        }
    }

    private static int mapPoint(int p, int delStart, int delEnd, int innerStart, int innerEnd, int delta) {
        if (p < delStart) return p;
        if (p <= innerStart) return delStart;
        if (p < innerEnd) return delStart + (p - innerStart);
        if (p <= delEnd) return delStart + (innerEnd - innerStart);
        return p + delta;
    }

    /** 最终一次性应用全部 span（代码标记不渲染，零长度规格丢弃） */
    private static CharSequence assemble(StringBuilder text, List<SpanSpec> specs) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        for (SpanSpec spec : specs) {
            if (spec.span instanceof CodeSpan) continue;
            if (spec.end <= spec.start) continue;
            sb.setSpan(spec.span, spec.start, spec.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }
}
