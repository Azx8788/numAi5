package io.github.numai5.data;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.numai5.model.Chat;
import io.github.numai5.model.Message;
import io.github.numai5.model.Role;

/**
 * 聊天数据管理：当前会话、历史列表、Gson 文件持久化。
 * 步骤16：写入改用 SafeFileWriter（tmp + fsync + rename，写入中崩溃不损坏原文件）；
 * 文件 IO 在后台单线程串行执行，主线程只做序列化快照（聊天集合仅在主线程增删）。
 */
public class ChatManager {
    private static final String TAG = "ChatManager";
    private static final String CHATS_FILE = "chats.json";

    private static ChatManager instance;
    private final Context appContext;
    private final List<Chat> chats = new ArrayList<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private int saveCount;
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

    /** 备份恢复：整体替换全部聊天记录 */
    public void replaceAll(List<Chat> newChats) {
        chats.clear();
        if (newChats != null) {
            for (Chat c : newChats) {
                if (c.messages == null) c.messages = new ArrayList<>();
                if (c.id == null || c.id.length() == 0) c.id = UUID.randomUUID().toString();
                chats.add(c);
            }
        }
        currentChat = chats.isEmpty() ? null : chats.get(0);
        if (currentChat == null) {
            startNewChat();
        }
        saveAll();
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
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.length() > 20) t = t.substring(0, 20) + "...";
        return t;
    }

    private Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    /** 序列化快照（调用线程）+ 后台原子写入（步骤16） */
    public void saveAll() {
        final String json = gson().toJson(chats);
        saveExecutor.execute(() -> writeToFile(json));
    }

    /** 崩溃安全写入：tmp + fsync + rename；每 20 次保存额外留一份 .bak */
    private void writeToFile(String json) {
        File file = new File(appContext.getFilesDir(), CHATS_FILE);
        boolean backup = (++saveCount % 20 == 0);
        try {
            SafeFileWriter.write(file, json.getBytes(StandardCharsets.UTF_8), backup);
        } catch (IOException e) {
            Log.e(TAG, "save chats failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadAll() {
        File file = new File(appContext.getFilesDir(), CHATS_FILE);
        List<Chat> loaded = parseFile(file);
        if (loaded == null) {
            loaded = parseFile(new File(file.getAbsolutePath() + ".bak"));
        }
        if (loaded != null) {
            chats.addAll(loaded);
            for (Chat c : chats) {
                if (c.messages == null) c.messages = new ArrayList<>();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Chat> parseFile(File file) {
        if (file == null || !file.exists()) return null;
        try {
            byte[] bytes = SafeFileWriter.read(file);
            String json = new String(bytes, StandardCharsets.UTF_8);
            return gson().fromJson(json, new com.google.gson.reflect.TypeToken<List<Chat>>() {}.getType());
        } catch (IOException | JsonSyntaxException e) {
            Log.e(TAG, "load chats failed: " + file.getName(), e);
            return null;
        }
    }
}
