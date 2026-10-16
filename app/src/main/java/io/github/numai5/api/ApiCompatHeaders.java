package io.github.numai5.api;

import java.util.Locale;
import okhttp3.Request;

/**
 * 部分中转站会对请求做「客户端指纹」校验：只放行官方客户端的请求，普通 HTTP 客户端
 * 一律返回 401（如 agentrouter.org 的 "unauthorized client detected"）。
 * 这类站点需要携带其认可的来源标识头才能调用，这里按域名自动补上，用户无需额外配置。
 *
 * 目前覆盖：agentrouter.org（社区通行做法：携带 Codex CLI 标识头）。
 * 若将来遇到其它同类站点，在此追加对应规则即可。
 */
public final class ApiCompatHeaders {

    private static final String AGENTROUTER_HOST = "agentrouter.org";
    private static final String CODEX_UA =
            "codex_cli_rs/0.101.0 (Mac OS 26.0.1; arm64) Apple_Terminal/464";

    private ApiCompatHeaders() {
    }

    /** 按请求地址判断并补上兼容头（仅影响已知站点，其它地址原样不动） */
    public static void apply(Request.Builder builder, String url) {
        if (builder == null || url == null) return;
        if (!url.toLowerCase(Locale.ROOT).contains(AGENTROUTER_HOST)) return;
        builder.header("Originator", "codex_cli_rs");
        builder.header("User-Agent", CODEX_UA);
        builder.header("Version", "0.101.0");
    }
}
