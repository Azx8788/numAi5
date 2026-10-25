package io.github.numai5.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个本地记忆文件。内容是一段 Markdown，可选 frontmatter 提供标题、描述、别名与固定标记。
 *
 * 版本号取内容 UTF-8 字节的 SHA-256 前 12 位，用作写入时的乐观锁。
 * frontmatter 解析只支持单行 key: value 与 [a, b]，不引入 YAML 依赖。
 */
public class MemoryFile {

    public static final String PROFILE_PATH = "/profile.md";
    public static final String PREFERENCES_PATH = "/preferences.md";
    public static final int MAX_BYTES = 48 * 1024;
    public static final int PINNED_MAX_BYTES = 4 * 1024;
    public static final int MAX_PATH_LENGTH = 128;

    private static final String[] KNOWN_KEYS = {"name", "title", "description", "aliases", "pinned"};

    public final String path;
    public final String content;
    public final long updatedAt;

    private final Map<String, String> frontmatter;
    private final String body;
    private final String title;
    private final String description;
    private final List<String> aliases;
    private final boolean pinned;
    private final String version;

    public MemoryFile(String path, String content, long updatedAt) {
        this.path = path;
        this.content = content == null ? "" : content;
        this.updatedAt = updatedAt;

        Map<String, String> fm = new LinkedHashMap<>();
        String rest = this.content;
        if (this.content.startsWith("---")) {
            int firstLineEnd = this.content.indexOf('\n');
            if (firstLineEnd > 0) {
                int end = findFrontmatterEnd(this.content, firstLineEnd + 1);
                if (end >= 0) {
                    String block = this.content.substring(firstLineEnd + 1, end);
                    parseBlock(block, fm);
                    int bodyStart = this.content.indexOf('\n', end);
                    rest = bodyStart >= 0 ? this.content.substring(bodyStart + 1) : "";
                }
            }
        }
        this.frontmatter = Collections.unmodifiableMap(fm);
        this.body = rest;
        this.title = firstNonEmpty(fm.get("title"), humanize(nameOf(path)));
        this.description = fm.get("description") == null ? "" : fm.get("description").trim();
        this.aliases = parseAliases(fm.get("aliases"));
        this.pinned = "true".equalsIgnoreCase(trim(fm.get("pinned")));
        this.version = versionOf(this.content);
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<String> aliases() {
        return aliases;
    }

    public boolean pinned() {
        return pinned;
    }

    public String body() {
        return body;
    }

    public String version() {
        return version;
    }

    public int byteSize() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    public Map<String, String> extraFrontmatter() {
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : frontmatter.entrySet()) {
            if (!isKnownKey(e.getKey())) extra.put(e.getKey(), e.getValue());
        }
        return extra;
    }

    public static String nameOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    public static String versionOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "000000000000";
        }
    }

    /**
     * 规范化记忆路径：必须是 .md，禁止 ..、反斜杠、控制字符、空段、点开头段与首尾空白段。
     * 返回 null 表示不合法。
     */
    public static String normalizePath(String raw) {
        if (raw == null) return null;
        String path = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (path.length() == 0) return null;
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > MAX_PATH_LENGTH || !path.endsWith(".md")) return null;
        if (path.indexOf('\\') >= 0) return null;
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) return null;
        }
        String[] segments = path.substring(1).split("/", -1);
        if (segments.length == 0) return null;
        for (String segment : segments) {
            if (segment.length() == 0) return null;
            if (!segment.equals(segment.trim())) return null;
            if (segment.startsWith(".")) return null;
            if (segment.endsWith(" .md")) return null;
        }
        return path;
    }

    /**
     * 用编辑页字段拼出完整文件内容。未知 frontmatter 键原样保留，避免导入文件保存后丢字段。
     */
    public static String compose(String path, String title, String description,
                                 List<String> aliases, boolean pinned, String body,
                                 Map<String, String> extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(oneLine(nameOf(path))).append('\n');
        if (notEmpty(title)) sb.append("title: ").append(oneLine(title)).append('\n');
        if (notEmpty(description)) sb.append("description: ").append(oneLine(description)).append('\n');
        if (aliases != null && !aliases.isEmpty()) {
            sb.append("aliases: [");
            for (int i = 0; i < aliases.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(oneLine(aliases.get(i)));
            }
            sb.append("]\n");
        }
        if (pinned) sb.append("pinned: true\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || isKnownKey(e.getKey())) continue;
                sb.append(oneLine(e.getKey())).append(": ").append(oneLine(e.getValue())).append('\n');
            }
        }
        sb.append("---\n");
        if (body != null && body.length() > 0) {
            sb.append(body);
            if (!body.endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }

    private static int findFrontmatterEnd(String content, int from) {
        int cursor = from;
        while (cursor <= content.length()) {
            int lineEnd = content.indexOf('\n', cursor);
            String line = lineEnd >= 0 ? content.substring(cursor, lineEnd) : content.substring(cursor);
            if ("---".equals(line.trim())) return cursor;
            if (lineEnd < 0) return -1;
            cursor = lineEnd + 1;
        }
        return -1;
    }

    private static void parseBlock(String block, Map<String, String> out) {
        String[] lines = block.split("\n", -1);
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();
            if (key.length() == 0) continue;
            out.put(key, unquote(value));
        }
    }

    private static List<String> parseAliases(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return Collections.unmodifiableList(result);
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.trim().length() == 0) return Collections.unmodifiableList(result);
        String[] parts = value.split(",");
        for (String part : parts) {
            String item = unquote(part.trim());
            if (item.length() > 0 && !result.contains(item)) result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    private static boolean isKnownKey(String key) {
        for (String known : KNOWN_KEYS) {
            if (known.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String humanize(String name) {
        if (name == null || name.length() == 0) return "";
        String spaced = name.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.length() == 0) return "";
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean notEmpty(String value) {
        return value != null && value.trim().length() > 0;
    }

    private static String firstNonEmpty(String a, String b) {
        return notEmpty(a) ? a.trim() : (b == null ? "" : b);
    }
}