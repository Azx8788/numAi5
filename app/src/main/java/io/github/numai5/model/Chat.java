package io.github.numai5.model;

import java.util.ArrayList;
import java.util.List;

public class Chat {
    public String id;
    public String title;
    public long createdAt;
    public long updatedAt;
    public List<Message> messages = new ArrayList<>();

    public Chat() {
    }

    public Chat(String id, String title, long createdAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }
}
