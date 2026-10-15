package io.github.numai5.model;

public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public static Role fromString(String s) {
        if (s == null) return USER;
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(s)) return r;
        }
        return USER;
    }
}
