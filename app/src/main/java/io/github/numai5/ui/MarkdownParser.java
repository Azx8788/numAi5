package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.numai5.R;

/**
 * 简化版 Markdown → Spannable（步骤18 重写；后续加入 LaTeX 公式渲染）。
 *
 * 实现要点：文本加工（去标记）只在普通字符串上进行，所有样式先记录为
 * (start, end, span) 规格；每次替换文本时按映射函数同步更新已有规格坐标；
 * 最终一次性在同一个 SpannableStringBuilder 上应用全部 span，不再有清空重建。
 * 处理顺序：公式提取（MathExtractor，先换成占位符，防止公式里的字符被 Markdown 规则破坏）→
 * 围栏代码块（块级，内容不参与 inline）→ 行内代码（加保护标记）→ 粗体 → 斜体 → 删除线 → 链接
 * → 最后把公式占位符替换为渲染好的图片（MathSpan）。
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
    /** 数学公式占位符：\u0001 + 序号 + \u0001（由 MathExtractor 生成） */
    private static final Pattern MATH_PLACEHOLDER = Pattern.compile("\u0001(\\d+)\u0001");

    public static CharSequence parse(Context context, String text, boolean isGenerating) {
        int color = 0xFF1A1A1A;
        float textSizePx = 15f * 2.75f;
        try {
            color = ContextCompat.getColor(context, R.color.text_assistant);
            textSizePx = 15f * context.getResources().getDisplayMetrics().scaledDensity;
        } catch (Throwable ignored) {
        }
        return parse(context, text, isGenerating, color, textSizePx);
    }

    /** 公式超出可用宽度时需要单独成块、可横向滑动 */
    public static final class DisplayBlock {
        public static final int TYPE_TEXT = 0;
        public static final int TYPE_MATH = 1;

        public final int type;
        /** TYPE_TEXT：已解析好的富文本 */
        public final CharSequence text;
        /** TYPE_MATH：公式内容 */
        public final String latex;
        /** TYPE_MATH：原始文本（渲染失败时回退显示） */
        public final String raw;
        /** TYPE_MATH：是否块级公式 */
        public final boolean display;

        private DisplayBlock(int type, CharSequence text, String latex, String raw, boolean display) {
            this.type = type;
            this.text = text;
            this.latex = latex;
            this.raw = raw;
            this.display = display;
        }

        static DisplayBlock text(CharSequence text) {
            return new DisplayBlock(TYPE_TEXT, text, null, null, false);
        }

        static DisplayBlock math(MathExtractor.Segment segment) {
            return new DisplayBlock(TYPE_MATH, null, segment.latex, segment.raw, segment.display);
        }
    }

    /** 多宽的公式算「超宽」：可用宽度的 92% */
    private static final float WIDE_MATH_RATIO = 0.92f;

    /**
     * 把正文拆成「文本片段 + 超宽公式」的交替序列。
     *
     * 为什么：行内公式一旦长到超过屏幕宽度，等比压缩到屏宽会变得又小又扁，
     * 而且 TextView 里的图片无法横向滚动，超出部分直接看不见。
     * 因此超宽公式单独拆出来，由调用方用 HorizontalScrollView 按原始尺寸展示。
     *
     * 未超宽的公式仍走普通内联渲染（与 parse 行为一致）。
     */
    public static List<DisplayBlock> parseBlocks(Context context, String text, int color, float textSizePx) {
        List<DisplayBlock> blocks = new ArrayList<>();
        if (text == null || text.length() == 0) return blocks;

        MathExtractor.Result math = MathExtractor.extract(text);
        if (!math.hasMath()) {
            blocks.add(DisplayBlock.text(parse(context, text, false, color, textSizePx)));
            return blocks;
        }

        int available = availableWidth(context);
        List<MathExtractor.Segment> wide = new ArrayList<>();
        for (MathExtractor.Segment segment : math.segments) {
            // maxWidthPx 传 0：按原始尺寸渲染，仅用于测量
            Drawable drawable = MathRenderer.render(context, segment.latex, segment.display,
                    textSizePx, 0, color);
            if (drawable == null) continue;
            int width = drawable.getBounds().width();
            if (width > 0 && width > available * WIDE_MATH_RATIO) {
                wide.add(segment);
            }
        }

        if (wide.isEmpty()) {
            blocks.add(DisplayBlock.text(parse(context, text, false, color, textSizePx)));
            return blocks;
        }

        // 按超宽公式的位置把原文切开：文本段单独走 Markdown 解析，公式段独立成块
        int cursor = 0;
        for (MathExtractor.Segment segment : wide) {
            if (segment.start > cursor) {
                String piece = text.substring(cursor, segment.start);
                if (piece.trim().length() > 0) {
                    blocks.add(DisplayBlock.text(parse(context, piece, false, color, textSizePx)));
                }
            }
            blocks.add(DisplayBlock.math(segment));
            cursor = segment.end;
        }
        if (cursor < text.length()) {
            String piece = text.substring(cursor);
            if (piece.trim().length() > 0) {
                blocks.add(DisplayBlock.text(parse(context, piece, false, color, textSizePx)));
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(DisplayBlock.text(parse(context, text, false, color, textSizePx)));
        }
        return blocks;
    }

    /** 公式可用宽度（减去气泡与列表内边距） */
    private static int availableWidth(Context context) {
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        int padding = (int) (100 * context.getResources().getDisplayMetrics().density);
        int width = screen - padding;
        return width < 160 ? 160 : width;
    }

    /**
     * @param color      公式渲染颜色（跟随所在 TextView 的文字颜色）
     * @param textSizePx 所在 TextView 的字号（px），公式按此大小渲染
     */
    public static CharSequence parse(Context context, String text, boolean isGenerating,
                                     int color, float textSizePx) {
        if (text == null || text.length() == 0) return "";
        MathExtractor.Result math = MathExtractor.extract(text);
        StringBuilder work = new StringBuilder();
        List<SpanSpec> specs = new ArrayList<>();
        appendBlocks(work, specs, math.text);
        applyInlines(work, specs);
        return assemble(context, work, specs, math, color, textSizePx);
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
                if (sb.length() > lineStart) {
                    // 代码块内容用等宽字体（顶层样式，先加保护标记再加字体，顺序无关）
                    specs.add(new SpanSpec(lineStart, sb.length(), new TypefaceSpan("monospace")));
                }
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

    /** 最终一次性应用全部 span（代码标记不渲染，零长度规格丢弃），随后替换公式占位符 */
    private static CharSequence assemble(Context context, StringBuilder text, List<SpanSpec> specs,
                                         MathExtractor.Result math, int color, float textSizePx) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        for (SpanSpec spec : specs) {
            if (spec.span instanceof CodeSpan) continue;
            if (spec.end <= spec.start) continue;
            sb.setSpan(spec.span, spec.start, spec.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (math != null && math.hasMath()) {
            applyMathSpans(context, sb, math.segments, color, textSizePx);
        }
        return sb;
    }

    /** 把公式占位符替换为图片 Span；渲染失败时按原文回退。从后往前处理，避免坐标位移影响 */
    private static void applyMathSpans(Context context, SpannableStringBuilder sb,
                                       List<MathExtractor.Segment> segments, int color, float textSizePx) {
        Matcher m = MATH_PLACEHOLDER.matcher(sb);
        List<int[]> found = new ArrayList<>(); // {start, end, segmentIndex}
        while (m.find()) {
            int index;
            try {
                index = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (index >= 0 && index < segments.size()) {
                found.add(new int[]{m.start(), m.end(), index});
            }
        }
        if (found.isEmpty()) return;

        int maxWidth = availableWidth(context);

        for (int k = found.size() - 1; k >= 0; k--) {
            int[] pos = found.get(k);
            MathExtractor.Segment segment = segments.get(pos[2]);
            Drawable drawable = MathRenderer.render(context, segment.latex, segment.display,
                    textSizePx, maxWidth, color);
            if (drawable != null) {
                sb.setSpan(new MathSpan(drawable, segment.raw), pos[0], pos[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.replace(pos[0], pos[1], segment.raw);
            }
        }
    }
}
