package io.github.numai5.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模型能力注册表（借鉴 RikkaHub ModelRegistry 的声明式思路）。
 *
 * 设计原则：
 * - /models 拉回来的模型只携带 id，能力（思考/识图/绘图/工具…）全部来自这里的本地知识库，
 *   不依赖网络响应里的猜测性字段；
 * - 规则用「分词序列」描述（tokens/notTokens），而不是 contains("o1") 这类裸字符串——
 *   例如 tokens("gpt","4","o") 只匹配 gpt-4o 系列的顺序分词，不会误伤 gpt-4o1、4o 前缀的其它模型；
 * - 手动标签（用户勾选）永远优先，这里只是默认值。
 */
public final class ModelRegistry {

    // ===== 能力位 =====
    public static final int THINKING = 1;
    public static final int VISION = 1 << 1;
    public static final int IMAGE_GEN = 1 << 2;
    /** 黑名单：命中则不支持 function calling */
    public static final int NO_TOOLS = 1 << 3;
    /** DeepSeek V4 等：历史上需要回传 reasoning_content */
    public static final int KEEP_REASONING = 1 << 4;
    /** 需要用 max_completion_tokens（o系 / gpt-5） */
    public static final int MAX_COMPLETION = 1 << 5;
    /** 纯推理模型：不接受 temperature */
    public static final int REASONING_ONLY = 1 << 6;

    /** 一条规则：positive 序列全部按序命中、negative 序列一个都不命中，则规则生效 */
    private static final class Rule {
        final String[][] positive; // 每个元素是一组备选（"a|b" 展开）
        final String[][] negative;
        final int flags;

        Rule(String[][] positive, String[][] negative, int flags) {
            this.positive = positive;
            this.negative = negative;
            this.flags = flags;
        }

        boolean match(List<String> tokens) {
            for (String[] spec : positive) {
                if (!matchSequence(tokens, spec)) return false;
            }
            for (String[] spec : negative) {
                if (matchSequence(tokens, spec)) return false;
            }
            return true;
        }
    }

    /** 推断结果 */
    public static final class Capabilities {
        public final boolean thinking;
        public final boolean vision;
        public final boolean imageGen;
        public final boolean tools;
        public final boolean keepReasoning;
        public final boolean maxCompletion;
        public final boolean reasoningOnly;

        Capabilities(int flags) {
            thinking = (flags & THINKING) != 0;
            vision = (flags & VISION) != 0;
            imageGen = (flags & IMAGE_GEN) != 0;
            tools = (flags & NO_TOOLS) == 0;
            keepReasoning = (flags & KEEP_REASONING) != 0;
            maxCompletion = (flags & MAX_COMPLETION) != 0;
            reasoningOnly = (flags & REASONING_ONLY) != 0;
        }
    }

    private static final List<Rule> RULES = buildRules();

    private ModelRegistry() {
    }

    /** 推断模型能力（模型 id 大小写不敏感） */
    public static Capabilities infer(String modelId) {
        if (modelId == null || modelId.length() == 0) return new Capabilities(0);
        List<String> tokens = tokenize(modelId);
        int flags = 0;
        for (Rule rule : RULES) {
            if (rule.match(tokens)) {
                flags |= rule.flags;
            }
        }
        return new Capabilities(flags);
    }

    /** 把推断结果写到 ModelDef（保留用户手动标签优先） */
    public static void applyTo(ModelDef m, String rawId) {
        if (m == null || rawId == null) return;
        Capabilities caps = infer(rawId);
        m.supportsThinking = caps.thinking;
        m.supportsVision = caps.vision;
        m.supportsImageGen = caps.imageGen;
        if (!caps.tools) m.supportsTools = false;
        m.keepReasoningInHistory = caps.keepReasoning;
        m.needsMaxCompletionTokens = caps.maxCompletion;
        m.isReasoningOnly = caps.reasoningOnly;
    }

    // ===== 规则（声明式） =====

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // —— 思考能力 ——
        r(rules, THINKING, "reasoner");
        r(rules, THINKING, "r", "1");
        r(rules, THINKING, "thinking");
        r(rules, THINKING, "qwq");
        r(rules, THINKING, "o", "1");
        r(rules, THINKING, "o", "3");
        r(rules, THINKING, "opus");
        r(rules, THINKING, "sonnet");
        r(rules, THINKING, "deepseek", "v", "4");
        r(rules, THINKING, "qwen", "3", "5");
        r(rules, THINKING, "qwen", "3", "vl");
        r(rules, THINKING, "gpt", "5", "4");
        // 商汤日日新及其聚合模型
        r(rules, THINKING, "sensenova", "6", "8");
        r(rules, THINKING, "glm", "5", "2");
        r(rules, THINKING, "kimi", "k", "3");
        r(rules, THINKING, "deepseek", "flash");

        // —— 视觉能力 ——
        r(rules, VISION, "vision");
        r(rules, VISION, "vl");
        r(rules, VISION, "gpt", "4", "o");
        r(rules, VISION, "gpt", "4", "v");
        r(rules, VISION, "claude");
        r(rules, VISION, "gemini");
        r(rules, VISION, "qwen", "3", "vl");
        r(rules, VISION, "deepseek", "v", "4", "flash", "vision");
        r(rules, VISION, "sensenova", "6", "8");
        r(rules, VISION, "kimi", "k", "3");
        // deepseek-flash（V4.1）支持图片输入，但 deepseek-v4-flash 不支持：用负向规则排除
        rules.add(new Rule(new String[][]{{"deepseek", "flash"}},
                new String[][]{{"v", "4"}}, VISION));

        // —— 不支持 function calling 的黑名单 ——
        r(rules, NO_TOOLS, "deepseek", "reasoner");
        r(rules, NO_TOOLS, "qwq");

        // —— 需回传 reasoning_content ——
        r(rules, KEEP_REASONING, "deepseek", "v", "4");

        // —— 用 max_completion_tokens ——
        r(rules, MAX_COMPLETION, "o", "1");
        r(rules, MAX_COMPLETION, "o", "3");
        r(rules, MAX_COMPLETION, "gpt", "5");

        // —— 纯推理模型 ——
        r(rules, REASONING_ONLY, "o", "1");
        r(rules, REASONING_ONLY, "o", "3");
        r(rules, REASONING_ONLY, "gpt", "5", "4");
        r(rules, REASONING_ONLY, "qwq");

        // —— 绘图模型 ——
        r(rules, IMAGE_GEN, "dall", "e");
        r(rules, IMAGE_GEN, "gpt", "image");
        r(rules, IMAGE_GEN, "imagen");
        r(rules, IMAGE_GEN, "flux");
        r(rules, IMAGE_GEN, "stable", "diffusion");
        r(rules, IMAGE_GEN, "sdxl");
        r(rules, IMAGE_GEN, "sd", "3");
        r(rules, IMAGE_GEN, "midjourney");
        r(rules, IMAGE_GEN, "seedream");
        r(rules, IMAGE_GEN, "seededit");
        r(rules, IMAGE_GEN, "kolors");
        r(rules, IMAGE_GEN, "cogview");
        r(rules, IMAGE_GEN, "wanx");
        r(rules, IMAGE_GEN, "hunyuan", "image");
        r(rules, IMAGE_GEN, "qwen", "image");
        r(rules, IMAGE_GEN, "gemini", "image");
        r(rules, IMAGE_GEN, "imagegen");
        r(rules, IMAGE_GEN, "image", "gen");
        r(rules, IMAGE_GEN, "-", "image");
        r(rules, IMAGE_GEN, "nano", "banana");
        r(rules, IMAGE_GEN, "ideogram");
        r(rules, IMAGE_GEN, "recraft");
        // doubao 系列只有 seedream 是绘图模型（改进：不再把 doubao 文本模型误标为绘图）
        r(rules, IMAGE_GEN, "doubao", "seedream");
        r(rules, IMAGE_GEN, "t2", "i");
        r(rules, IMAGE_GEN, "text", "to", "image");
        r(rules, IMAGE_GEN, "文生图");
        r(rules, IMAGE_GEN, "绘图");
        r(rules, IMAGE_GEN, "画图");
        // 商汤 U 系列（sensenova-u1.5-lite / fast）：生成与编辑一体的图片模型
        r(rules, IMAGE_GEN, "sensenova", "u", "1");

        return rules;
    }

    private static void r(List<Rule> rules, int flags, String... tokens) {
        rules.add(new Rule(new String[][]{tokens}, new String[0][], flags));
    }

    // ===== 分词与序列匹配（移植 RikkaHub TokenSequenceMatcher） =====

    /** 与 RikkaHub tokenize 一致：字母串（含中文）、数字串、其它单字符分别成 token */
    private static List<String> tokenize(String modelId) {
        List<String> tokens = new ArrayList<>();
        String lower = modelId.toLowerCase(Locale.ROOT);
        int i = 0;
        int n = lower.length();
        while (i < n) {
            char c = lower.charAt(i);
            if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < n && Character.isLetter(lower.charAt(j))) j++;
                tokens.add(lower.substring(i, j));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && Character.isDigit(lower.charAt(j))) j++;
                tokens.add(lower.substring(i, j));
                i = j;
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }

    /** spec 里的一个元素按序在 tokens 中寻找；备选用 "|" 分隔（如 "r1|r-1"） */
    private static boolean matchSequence(List<String> tokens, String[] spec) {
        if (spec == null || spec.length == 0) return false;
        int si = 0;
        String[] alternatives = spec;
        for (String token : tokens) {
            boolean hit = false;
            String cur = alternatives[si];
            int bar = cur.indexOf('|');
            if (bar >= 0) {
                for (String alt : cur.split("\\|")) {
                    if (alt.length() > 0 && alt.equals(token)) { hit = true; break; }
                }
            } else if (cur.equals(token)) {
                hit = true;
            }
            if (hit) {
                si++;
                if (si == alternatives.length) return true;
            }
        }
        return false;
    }
}
