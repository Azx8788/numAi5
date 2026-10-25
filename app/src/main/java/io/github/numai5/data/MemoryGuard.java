package io.github.numai5.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆写入前的本地拦截：身份证号、银行卡号、SSN。
 *
 * 这是隐私护栏，不是安全边界：命中后界面提示，用户仍可确认保存。
 * 正则与校验逻辑为独立实现，未复制第三方项目代码。
 */
public final class MemoryGuard {

    private static final Pattern CHINESE_ID = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");
    private static final Pattern SSN = Pattern.compile(
            "(?<![\\d-])(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?![\\d-])");
    private static final Pattern CARD = Pattern.compile(
            "(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");

    private static final int[] ID_WEIGHTS = {
            7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2
    };
    private static final String ID_CHECK = "10X98765432";

    private MemoryGuard() {
    }

    /**
     * @return 命中的类别；没有命中返回 null。
     */
    public static String findForbidden(String text) {
        if (text == null || text.length() == 0) return null;
        if (containsChineseId(text)) return "身份证号";
        if (SSN.matcher(text).find()) return "SSN";
        if (containsCard(text)) return "银行卡号";
        return null;
    }

    private static boolean containsChineseId(String text) {
        Matcher m = CHINESE_ID.matcher(text);
        while (m.find()) {
            if (isValidChineseId(m.group())) return true;
        }
        return false;
    }

    private static boolean isValidChineseId(String id) {
        if (id == null || id.length() != 18) return false;
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return false;
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        char expected = ID_CHECK.charAt(sum % 11);
        char actual = Character.toUpperCase(id.charAt(17));
        return expected == actual;
    }

    private static boolean containsCard(String text) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            String digits = m.group().replace(" ", "").replace("-", "");
            if (digits.length() >= 13 && digits.length() <= 19 && isLuhnValid(digits)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) return false;
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}