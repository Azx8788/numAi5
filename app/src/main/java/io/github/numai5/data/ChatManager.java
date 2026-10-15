package io.github.numai5.data;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;

/**
 * 聊天数据管理：当前会话、历史列表、Gson 文件持久化（numAi 风格）。
 */
public class ChatManager {
    private static final String TAG = "ChatManager";
    private static final String CHATS_FILE = "chats.json";

    private static ChatManager instance;
    private final Context appContext;
    private final List<Chat> chats = new ArrayList<>();
    private Chat currentChat;

    private ChatManager(Context context) {
        this.appContext = context.getApplicationContext();
        loadAll();
        if (currentChat == null) {
            startNewChat();
        }
    }

    public static synchronized ChatManager getInstance(Context context) {
        if (instance == null) {
            instance = new ChatManager(context);
        }
        return instance;
    }

    public static ChatManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ChatManager not initialized");
        }
        return instance;
    }

    public Chat getCurrentChat() {
        return currentChat;
    }

    public void setCurrentChat(Chat chat) {
        this.currentChat = chat;
    }

    public void startNewChat() {
            Chat chat = new Chat(UUID.randomUUID().toString(), "", System.currentTimeMillis());
            this.currentChat = chat;
            chats.add(chat);
        }

    public List<Chat> getChats() {
        return new ArrayList<>(chats);
    }

    public List<Chat> getSortedChats() {
        List<Chat> sorted = new ArrayList<>(chats);
        Collections.sort(sorted, new Comparator<Chat>() {
            @Override
            public int compare(Chat a, Chat b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return sorted;
    }

    public void deleteChat(Chat chat) {
        if (chat == null) return;
        chats.remove(chat);
        if (currentChat == chat) {
            currentChat = chats.isEmpty() ? null : chats.get(0);
        }
        saveAll();
    }

    public void onMessageAdded(Chat chat) {
        if (chat == null) return;
        chat.updatedAt = System.currentTimeMillis();
        // 自动生成标题：取第一条用户消息
        if ((chat.title == null || chat.title.length() == 0) && !chat.messages.isEmpty()) {
            for (Message m : chat.messages) {
                if (m.getRoleEnum() == Role.USER && m.content != null && m.content.trim().length() > 0) {
                    chat.title = generateTitle(m.content);
                    break;
                }
            }
        }
        saveAll();
    }

    private String generateTitle(String text) {
        if (text == null) return "新对话";
        String t = text.trim().replaceAll("\s+", " ");
        if (t.length() > 20) t = t.substring(0, 20) + "...";
        return t;
    }

    private Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    public void saveAll() {
        File file = new File(appContext.getFilesDir(), CHATS_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            gson().toJson(chats, writer);
        } catch (IOException e) {
            Log.e(TAG, "save chats failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadAll() {
        File file = new File(appContext.getFilesDir(), CHATS_FILE);
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            List<Chat> loaded = gson().fromJson(reader, new com.google.gson.reflect.TypeToken<List<Chat>>() {}.getType());
            if (loaded != null) {
                chats.addAll(loaded);
                for (Chat c : chats) {
                    if (c.messages == null) c.messages = new ArrayList<>();
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            Log.e(TAG, "load chats failed", e);
        }
    }
}
