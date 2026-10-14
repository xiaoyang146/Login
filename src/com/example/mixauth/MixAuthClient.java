package com.example.mixauth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * MixAuth HTTP 客户端（纯 Java，基于 java.net.http）。
 *
 * MixAuth: https://github.com/InvertGeek/mixauth
 * 提供三个接口（JSON，POST）：
 *   POST /api/qr      {"type":"qq|wechat"}           -> data.id + data.qrcode
 *   POST /api/status  {"type":"qq|wechat","id":"..."} -> data.* 登录状态 + signData（HMAC 签名）
 *   POST /api/verify  {"sign":"..."}                  -> 签名校验（API 接入可不调用）
 */
public final class MixAuthClient {

    /** MixAuth 公开演示实例，按顺序尝试，第一个连通的为准。生产使用建议自行部署。 */
    public static final List<String> DEFAULT_BASE_URLS = List.of(
            "https://occupational-hare-dwa-b4b31470.koyeb.app",
            "https://mixauth.onrender.com"
    );

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final List<String> baseUrls;
    private String activeBaseUrl;

    public MixAuthClient(List<String> baseUrls) {
        this.baseUrls = baseUrls == null || baseUrls.isEmpty() ? DEFAULT_BASE_URLS : baseUrls;
    }

    public String baseUrl() {
        return activeBaseUrl;
    }

    /** POST JSON 到 /api/qr，自动选择可用的服务实例。 */
    public Json.Object getQr(String type) throws IOException, InterruptedException {
        String body = "{\"type\":\"" + type + "\"}";
        IOException last = null;
        for (String url : baseUrls) {
            try {
                Json.Object resp = postJson(url + "/api/qr", body);
                this.activeBaseUrl = url;
                return resp;
            } catch (IOException ex) {
                last = ex;
            }
        }
        throw new IOException("所有 MixAuth 实例均连接失败: " + (last == null ? "" : last.getMessage()), last);
    }

    /** POST JSON 到 /api/status 轮询登录状态。 */
    public Json.Object queryStatus(String type, String id) throws IOException, InterruptedException {
        String body = "{\"type\":\"" + type + "\",\"id\":" + Json.quoteStr(id) + "}";
        return postJson(activeBaseUrl + "/api/status", body);
    }

    /** POST JSON 校验签名（可选，API 接入一般无需校验）。 */
    public Json.Object verify(String sign) throws IOException, InterruptedException {
        return postJson(activeBaseUrl + "/api/verify", "{\"sign\":" + Json.quoteStr(sign) + "}");
    }

    private Json.Object postJson(String url, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (MixAuthJavaClient/1.0; Java) AppleWebKit/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " @ " + url + " -> " + resp.body());
        }
        Json.Object json = Json.parse(resp.body());
        if (!json.optBoolean("success", true)) {
            throw new IOException("接口返回失败: " + resp.body());
        }
        return json;
    }

    /**
     * 下载二维码图片字节。
     * QQ 返回的是 data:image/png;base64,... 的 Data URL；微信返回的是 https 图片地址。
     */
    public byte[] downloadQrImage(String qrcode) throws IOException, InterruptedException {
        if (qrcode == null || qrcode.isEmpty()) {
            throw new IOException("二维码内容为空");
        }
        if (qrcode.startsWith("data:")) {
            int idx = qrcode.indexOf("base64,");
            if (idx < 0) {
                throw new IOException("无法解析的 Data URL: " + qrcode.substring(0, Math.min(60, qrcode.length())) + "...");
            }
            return Base64.getDecoder().decode(qrcode.substring(idx + 7));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(qrcode))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (MixAuthJavaClient/1.0; Java) AppleWebKit/537.36")
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("二维码图片下载失败: HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 把二维码图片保存为文件（按魔数识别 PNG/JPEG），返回保存路径。 */
    public String saveQrImage(byte[] data, String type) throws IOException {
        String ext = "png";
        if (data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            ext = "jpg"; // JPEG
        }
        Path p = Path.of(System.getProperty("user.dir"), "mixauth-qr-" + type + "." + ext);
        Files.write(p, data);
        return p.toAbsolutePath().toString();
    }

    // ---------- 测试辅助 ----------
    public static void main(String[] args) throws Exception {
        // 简单自检：不依赖网络，仅验证 JSON 解析器与 Data URL 解码
        String sample = "{\"data\":{\"code\":\"66\",\"message\":\"二维码未失效。\",\"arr\":[1,2.5,\"x\",null,true],\"authType\":\"qq\"},"
                + "\"signData\":\"ts|aaaa|bbbb\",\"success\":true}";
        Json.Object json = Json.parse(sample);
        Json.Object data = json.optObject("data");
        System.out.println("code=" + data.optString("code", "?"));
        System.out.println("message=" + data.optString("message", "?"));
        System.out.println("success=" + json.optBoolean("success", false));
        System.out.println("arr.size=" + ((Json.Array) data.get("arr")).size());

        byte[] fake = new byte[]{1, 2, 3, 4, 5};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(fake);
        MixAuthClient c = new MixAuthClient(null);
        byte[] back = c.downloadQrImage(dataUrl);
        System.out.println("b64 roundtrip ok=" + (back[0] == 1 && back[4] == 5 && back.length == 5));
        System.out.println("quoteStr=" + Json.quoteStr("{\"type\":\"qq\"}"));
        System.out.println("JSON 解析自检通过");
    }
}