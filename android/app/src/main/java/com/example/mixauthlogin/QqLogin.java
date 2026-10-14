package com.example.mixauthlogin;

import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * QQ互联 OAuth2.0 客户端（纯 Java，仅系统内置 HttpURLConnection + org.json）。
 *
 * 流程（官方标准 Web 授权流，适用于移动应用在 WebView 内完成授权）：
 *   1. graph.qq.com/oauth2.0/authorize?response_type=code...   -> 用户授权后回调 redirect_uri?code=xxx&state=xxx
 *   2. graph.qq.com/oauth2.0/token                             -> access_token
 *   3. graph.qq.com/oauth2.0/me                                -> openid
 *   4. graph.qq.com/user/get_user_info                         -> 昵称/头像等
 *
 * 注意：redirect_uri 必须与 QQ互联后台（connect.qq.com → 该移动应用 → 回调地址）保持一致。
 */
public final class QqLogin {

    public static final String APP_ID = BuildConfig.QQ_APP_ID;
    public static final String APP_KEY = BuildConfig.QQ_APP_KEY;
    public static final String REDIRECT_URI = BuildConfig.QQ_REDIRECT_URI;
    public static final String SCOPE = "get_user_info";

    /** 回调地址前缀，WebView 拦截用。 */
    public static final String CALLBACK_PREFIX = REDIRECT_URI;

    /** 生成授权页 URL（在 WebView 中打开）。 */
    public static String authorizeUrl(String state) {
        return "https://graph.qq.com/oauth2.0/authorize"
                + "?response_type=code&client_id=" + APP_ID
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&state=" + enc(state)
                + "&scope=" + SCOPE;
    }

    public static String newState() {
        String u = UUID.randomUUID().toString().replace("-", "");
        return u.substring(0, 16);
    }

    /** 解析 URL 查询参数。 */
    public static Map<String, String> parseQuery(String url) {
        Map<String, String> map = new HashMap<>();
        String q = url;
        int i = q.indexOf('?');
        if (i >= 0) {
            q = q.substring(i + 1);
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(Uri.decode(pair.substring(0, eq)), Uri.decode(pair.substring(eq + 1)));
            }
        }
        return map;
    }

    /**
     * 用授权码换取 access_token + openid + 用户信息。
     * 返回的 JSONObject 含 openid、access_token(截断) 及 get_user_info 的昵称/头像等字段。
     */
    public JSONObject exchange(String code) throws Exception {
        if (APP_KEY == null || APP_KEY.length() < 8) {
            throw new IllegalStateException(
                    "APP_KEY 未配置：本地构建请设置环境变量 QQ_APP_KEY，"
                            + "或使用 GitHub Actions 时在仓库配置 secrets.QQ_APP_KEY");
        }

        // 1) code -> access_token
        String tokenBody = httpGet("https://graph.qq.com/oauth2.0/token",
                "grant_type", "authorization_code",
                "client_id", APP_ID,
                "client_secret", APP_KEY,
                "code", code,
                "redirect_uri", REDIRECT_URI);
        Map<String, String> token = parsePairs(tokenBody);
        String accessToken = token.get("access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IOException("换取 access_token 失败: " + tokenBody);
        }

        // 2) access_token -> openid
        String meBody = httpGet("https://graph.qq.com/oauth2.0/me",
                "access_token", accessToken);
        JSONObject me = parseCallbackJson(meBody);
        String openid = me.optString("openid", "");
        if (openid.isEmpty()) {
            throw new IOException("获取 openid 失败: " + meBody);
        }

        // 3) openid -> 用户信息
        String userBody = httpGet("https://graph.qq.com/user/get_user_info",
                "access_token", accessToken,
                "oauth_consumer_key", APP_ID,
                "openid", openid);
        JSONObject user = new JSONObject(userBody);
        user.put("openid", openid);
        user.put("access_token",
                accessToken.length() > 12 ? accessToken.substring(0, 12) + "..." : accessToken);
        return user;
    }

    // ============================ HTTP 辅助 ============================

    private static String httpGet(String base, String... kv) throws Exception {
        StringBuilder url = new StringBuilder(base);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            url.append(i == 0 ? "?" : "&").append(enc(kv[i])).append("=").append(enc(kv[i + 1]));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = new String(readAll(is), "UTF-8");
            if (code != 200) {
                throw new IOException("HTTP " + code + ": " + body);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** key=value&key2=value2 解析；若响应本身是 JSON（fmt=json 报错时），按 JSON 解析。 */
    private static Map<String, String> parsePairs(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        String t = body.trim();
        if (t.startsWith("{")) {
            try {
                JSONObject j = new JSONObject(t);
                java.util.Iterator<String> it = j.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    map.put(k, j.optString(k));
                }
                return map;
            } catch (Exception ignored) {
                // 落到 key=value 解析
            }
        }
        for (String pair : t.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(pair.substring(0, i), Uri.decode(pair.substring(i + 1)));
            }
        }
        return map;
    }

    /** 剥离 JSONP 的 callback( ... ); 外壳。 */
    private static JSONObject parseCallbackJson(String body) throws Exception {
        String t = body.trim();
        int a = t.indexOf('(');
        int b = t.lastIndexOf(')');
        if (a >= 0 && b > a) {
            t = t.substring(a + 1, b).trim();
        }
        return new JSONObject(t);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}