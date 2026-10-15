package io.github.numai5.data;

import java.util.ArrayList;
import java.util.List;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;

/**
 * 上下文截断策略（B3）：保留 system + 最近 N 轮 + 字符预算上限。
 * 发生裁剪时返回 true，UI 可据此给出轻提示。
 */
public class ContextTrimmer {

    /** 默认保留最近 12 轮（user/assistant 各算一轮） */
    private static final int DEFAULT_MAX_ROUNDS = 12;
    /** 字符预算，按约 4 chars/token 估算，默认 ~32k tokens */
    private static final int DEFAULT_MAX_CHARS = 32_000 * 4;

    public static boolean trim(List<Message> source, List<Message> target) {
        return trim(source, target, DEFAULT_MAX_ROUNDS, DEFAULT_MAX_CHARS);
    }

    public static boolean trim(List<Message> source, List<Message> target,
                               int maxRounds, int maxChars) {
        target.clear();
        if (source == null || source.isEmpty()) return false;

        Message system = null;
        int firstUser = -1;
        for (int i = 0; i < source.size(); i++) {
            Message m = source.get(i);
            if (m.getRoleEnum() == Role.SYSTEM) {
                system = m;
                break;
            }
            if (m.getRoleEnum() == Role.USER && firstUser == -1) firstUser = i;
        }

        List<Message> body = new ArrayList<>();
        for (Message m : source) {
            if (m.getRoleEnum() != Role.SYSTEM) body.add(m);
        }

        // 保留最近 maxRounds 轮（从后往前数完整的 assistant 回复）
        List<Message> recent = new ArrayList<>();
        int assistantCount = 0;
        for (int i = body.size() - 1; i >= 0; i--) {
            Message m = body.get(i);
            recent.add(0, m);
            if (m.getRoleEnum() == Role.ASSISTANT) assistantCount++;
            if (assistantCount >= maxRounds) break;
        }
        boolean truncated = body.size() != recent.size();

        if (system != null) target.add(system);
        target.addAll(recent);

        // 字符预算裁剪
        int totalChars = 0;
        for (Message m : target) {
            totalChars += charCount(m);
        }
        while (totalChars > maxChars && target.size() > (system != null ? 2 : 1)) {
            Message removed = target.remove(system != null ? 1 : 0);
            totalChars -= charCount(removed);
            truncated = true;
        }

        return truncated;
    }

    private static int charCount(Message m) {
        if (m == null) return 0;
        int c = 0;
        if (m.content != null) c += m.content.length();
        if (m.reasoningRaw != null) c += m.reasoningRaw.length();
        if (m.inputImages != null) c += m.inputImages.size() * 100; // 图片占位
        return c;
    }
}
