package io.github.numai5.thinking;

import io.github.numai5.model.ThinkingLevel;

/**
 * 四档 → reasoning_effort 映射（A3）。
 * - 标准模式：MAX 降级 high（"max" 非官方值，严格上游会 400）
 * - 方言模式(NewAPI)：MAX → max；开关来自 Config.newapiDialect（步骤5，默认开启，不支持时自动降级）
 * - DEFAULT 显式 medium（GPT-5.x 默认 none，不发=不思考）
 */
public final class ThinkingMapper {

    private ThinkingMapper() {
    }

    public static String toStandard(ThinkingLevel l) {
        switch (l) {
            case LOW: return "low";
            case DEFAULT: return "medium";
            case HIGH: return "high";
            case MAX: return "high"; // 标准模式 MAX 降级
            default: return null;
        }
    }

    public static String toDialect(ThinkingLevel l) {
        switch (l) {
            case LOW: return "low";
            case DEFAULT: return "medium";
            case HIGH: return "high";
            case MAX: return "max"; // NewAPI 方言
            default: return null;
        }
    }

    public static String apply(ThinkingLevel l, String effortDialect) {
        if (l == null) return null;
        if (effortDialect == null || "standard".equals(effortDialect)) {
            return toStandard(l);
        }
        return toDialect(l);
    }
}
