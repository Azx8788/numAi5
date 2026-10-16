package io.github.numai5.data;

import java.util.ArrayList;
import java.util.List;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;

/**
 * 上下文截断策略（B3 / 步骤17）：保留全部系统消息 + 最近 N 个完整回合 + 当前消息，
 * 再做字符预算裁剪。裁剪只影响本次请求的上下文，不改动本地聊天历史。
 * 返回 true 表示发生过裁剪，UI 可轻提示。
 */
public class ContextTrimmer {

    /** 默认保留最近 12 个完整回合 */
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

        // 系统消息全部保留（通常位于最前）
        List<Message> systems = new ArrayList<>();
        List<Message> body = new ArrayList<>();
        for (Message m : source) {
            if (m.getRoleEnum() == Role.SYSTEM) systems.add(m);
            else body.add(m);
        }

        // 以最近一个完整回合为锚点：从后往前找第 maxRounds 个 assistant 回复，
        // 以它前面最近的 user 消息作为保留起点——保证不会从孤立的 assistant 开始。
        int start = 0;
        int kept = 0;
        for (int i = body.size() - 1; i >= 0; i--) {
            if (body.get(i).getRoleEnum() != Role.ASSISTANT) continue;
            kept++;
            if (kept >= maxRounds) {
                int userIdx = -1;
                for (int j = i - 1; j >= 0; j--) {
                    Role role = body.get(j).getRoleEnum();
                    if (role == Role.USER) { userIdx = j; break; }
                    if (role == Role.ASSISTANT) break; // 防御：异常数据，无对应提问
                }
                start = (userIdx >= 0) ? userIdx : i;
                break;
            }
        }
        boolean truncated = start > 0;

        for (Message s : systems) target.add(s);
        for (int i = start; i < body.size(); i++) target.add(body.get(i));

        // 字符预算裁剪：从最早的非系统消息开始删；若删的是 user 且紧随其后的正是它的
        // assistant 回复，则一并删除——避免留下孤立回复。工具阶段沿用此规则：
        // assistant(tool_calls) 与对应 tool 结果消息同样不可拆开。
        int sysCount = systems.size();
        int totalChars = 0;
        for (Message m : target) totalChars += charCount(m);
        while (totalChars > maxChars) {
            int idx = sysCount;
            if (idx >= target.size()) break;
            Message first = target.get(idx);
            if (first.getRoleEnum() == Role.SYSTEM) break; // 防御
            boolean pair = first.getRoleEnum() == Role.USER
                    && idx + 1 < target.size()
                    && target.get(idx + 1).getRoleEnum() == Role.ASSISTANT;
            int removeCount = pair ? 2 : 1;
            if (target.size() - removeCount < sysCount + 1) break; // 至少保留最后一条消息
            totalChars -= charCount(first);
            if (pair) totalChars -= charCount(target.get(idx + 1));
            target.remove(idx);
            if (pair) target.remove(idx);
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
