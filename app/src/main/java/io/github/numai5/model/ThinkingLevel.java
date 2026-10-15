package io.github.numai5.model;

/** 思考等级四档（A3）。DEFAULT 必须显式发 medium，不能"不发字段"。 */
public enum ThinkingLevel {
    LOW("Low"),
    DEFAULT("Default"),
    HIGH("High"),
    MAX("MAX");

    private final String display;

    ThinkingLevel(String d) {
        this.display = d;
    }

    public String getDisplay() {
        return display;
    }

    public static ThinkingLevel fromName(String name) {
        if (name == null || name.length() == 0) return DEFAULT;
        try {
            return ThinkingLevel.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
