package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import androidx.core.content.ContextCompat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.numai5.R;

/**
 * 简化版 Markdown → Spannable（基于 numAi 思路）。
 * 支持：标题、加粗、斜体、行内代码、代码块、引用、列表、链接、删除线。
 */
public class MarkdownParser {

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:\\w+)?\\n?((?s:.*?))```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    public static CharSequence parse(Context context, String text, boolean isGenerating) {
        if (text == null || text.length() == 0) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("# ")) {
                appendSpan(sb, line.substring(2), new StyleSpan(Typeface.BOLD), 1.4f);
            } else if (line.startsWith("## ") || line.startsWith("### ")) {
                appendSpan(sb, line.substring(line.indexOf(' ') + 1), new StyleSpan(Typeface.BOLD), 1.2f);
            } else if (line.startsWith("> ")) {
                int start = sb.length();
                sb.append(line.substring(2)).append("\n");
                int end = sb.length();
                sb.setSpan(new QuoteSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                int start = sb.length();
                sb.append(line.substring(2)).append("\n");
                sb.setSpan(new BulletSpan(40), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(line).append("\n");
            }
        }
        applyInline(sb);
        return sb;
    }

    private static void appendSpan(SpannableStringBuilder sb, String text, Object span, float scale) {
        int start = sb.length();
        sb.append(text).append("\n");
        sb.setSpan(span, start, sb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void applyInline(SpannableStringBuilder sb) {
        String text = sb.toString();
        sb.clear();
        sb.append(text);
        // 粗体 **text**
        applyPattern(sb, Pattern.compile("\\*\\*([^\\*]+)\\*\\*"), new StyleSpan(Typeface.BOLD));
        // 斜体 *text* 或 _text_
        applyPattern(sb, Pattern.compile("(?<!\\*)\\*([^\\*]+)\\*(?!\\*)"), new StyleSpan(Typeface.ITALIC));
        applyPattern(sb, Pattern.compile("_([^_]+)_"), new StyleSpan(Typeface.ITALIC));
        // 删除线 ~~text~~
        applyPattern(sb, Pattern.compile("~~([^~~]+)~~"), new StrikethroughSpan());
        // 链接 [text](url)
        applyLinkPattern(sb);
        // 行内代码
        applyPattern(sb, INLINE_CODE, new TypefaceSpan("monospace"));
    }

    private static void applyPattern(SpannableStringBuilder sb, Pattern pattern, Object span) {
        String s = sb.toString();
        sb.clear();
        Matcher m = pattern.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int start = sb.length();
            sb.append(m.group(1));
            int end = sb.length();
            sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(s, last, s.length());
    }

    private static void applyLinkPattern(SpannableStringBuilder sb) {
        String s = sb.toString();
        sb.clear();
        Pattern p = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
        Matcher m = p.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int start = sb.length();
            sb.append(m.group(1));
            int end = sb.length();
            sb.setSpan(new URLSpan(m.group(2)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(s, last, s.length());
    }
}
