package io.github.numai5.data;

import java.util.List;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;

/** 消息构造辅助 */
public class MessageManager {

    public static Message createUserMessage(String content, List<String> inputImages) {
        Message m = new Message(Role.USER);
        m.content = content == null ? "" : content;
        m.inputImages = inputImages;
        return m;
    }

    public static Message createAssistantMessage(String model, String provider, String thinkingLevel) {
        Message m = new Message(Role.ASSISTANT);
        m.model = model;
        m.provider = provider;
        m.thinkingLevel = thinkingLevel;
        return m;
    }

    public static Message createSystemMessage(String content) {
        Message m = new Message(Role.SYSTEM);
        m.content = content == null ? "" : content;
        return m;
    }

    public static Message createToolMessage(String toolCallId, String content) {
        Message m = new Message(Role.TOOL);
        m.toolCallId = toolCallId;
        m.content = content == null ? "" : content;
        return m;
    }

    public static Message findToolResponse(List<Message> messages, String toolCallId) {
        if (toolCallId == null || messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getRoleEnum() == Role.TOOL && toolCallId.equals(m.toolCallId)) {
                return m;
            }
        }
        return null;
    }
}
