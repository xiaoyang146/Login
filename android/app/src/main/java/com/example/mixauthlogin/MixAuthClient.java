package com.example.mixauthlogin;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 * MixAuth HTTP 客户端（Android 纯 Java 实现，仅用系统内置 HttpURLConnection + org.json）。
 *
 * MixAuth: https://github.com/InvertGeek/mixauth
 *   POST /api/qr     {"type":"qq|wechat"}            -> data.id + data.qrcode
 *   POST /api/status {"type","id"}                    -> data.* 登录状态
 */
public final class MixAuthClient {

    /** 公开演示实例，按顺序尝试，第一个连通的为准。生产使用建议自行部署。 */
    public static final List<String> DEFAULT_BASE_URLS = Arrays.asList(
            "https://occupational-hare-dwa-b4b31470.koyeb.app",
            "https://mixauth.onrender.com"
    );

    private final List<String> baseUrls;
    private String activeBaseUrl;

    public MixAuthClient() {
        this.baseUrls = DEFAULT_BASE_URLS;
    }

    /**
     * 获取登录二维码，返回 data 对象（含 id 与 qrcode）。
     */
    public JSONObject getQr(String type) throws Exception {
        JSONObject body = new JSONObject();
        body.put("type", type);
        Exception last = null;
        for (String url : baseUrls) {
            try {
                JSONObject resp = postJson(url + "/api/qr", body);
                activeBaseUrl = url;
                return resp.getJSONObject("data");
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("所有 MixAuth 实例均连接失败", last);
    }

    /**
     * 轮询登录状态，返回完整响应对象（含 data 与 signData）。
     */
    public JSONObject status(String type, String id) throws Exception {
        JSONObject body = new JSONObject();
        body.put("type", type);
        body.put("id", id);
        return postJson(activeBaseUrl + "/api/status", body);
    }

    /**
     * 获取二维码图片字节。
     * QQ 返回 data:image/png;base64,... 的数据地址；微信返回 https 图片地址。
     */
    public byte[] fetchQrImage(String qrcode) throws Exception {
        if (qrcode == null || qrcode.isEmpty()) {
            throw new IOException("二维码内容为空");
        }
        if (qrcode.startsWith("data:")) {
            int idx = qrcode.indexOf("base64,");
            if (idx < 0) {
                throw new IOException("无法解析 Data URL");
            }
            return Base64.decode(qrcode.substring(idx + 7), Base64.DEFAULT);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(qrcode).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "MixAuthAndroid/1.0");
            int code = c.getResponseCode();
            byte[] data = readAllBytes(c.getInputStream());
            if (code != 200) {
                throw new IOException("二维码图片下载失败: HTTP " + code);
            }
            return data;
        } finally {
            c.disconnect();
        }
    }

    private JSONObject postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(35000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "MixAuthAndroid/1.0");
            c.setDoOutput(true);

            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String text = new String(readAllBytes(is), "UTF-8");
            if (code != 200) {
                throw new IOException("HTTP " + code + ": " + text);
            }
            JSONObject obj = new JSONObject(text);
            if (!obj.optBoolean("success", true)) {
                throw new IOException("接口返回失败: " + text);
            }
            return obj;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}