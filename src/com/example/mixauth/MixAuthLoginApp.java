package com.example.mixauth;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MixAuth 登录测试应用（纯 Java / JDK 17+，零第三方依赖）。
 *
 * 用法:
 *   java -cp out com.example.mixauth.MixAuthLoginApp [qq|wechat] [选项]
 *
 * 选项:
 *   --type qq|wechat            登录方式（默认 qq，也可用位置参数指定）
 *   --no-gui                    无图形环境模式：不弹 Swing 窗口，仅保存二维码图片文件
 *   --timeout <秒>              整体最长轮询时间（默认 180）
 *   --interval <毫秒>           轮询间隔（默认 qq=2000 / wechat=3000）
 *   --base-url <url>            指定 MixAuth 服务地址（默认自动从公开演示实例中选择）
 *
 * 流程: 获取二维码 -> 展示/保存二维码 -> 轮询登录状态 -> 输出用户信息
 */
public final class MixAuthLoginApp {

    private static final class Config {
        String type = "qq";
        boolean gui;
        boolean guiSet;
        int timeoutSec = 180;
        long intervalMs = -1;
        List<String> baseUrls = null;
    }

    public static void main(String[] args) {
        int code;
        try {
            code = new MixAuthLoginApp().run(parseArgs(args));
        } catch (Exception ex) {
            System.err.println("[错误] " + ex.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    private int run(Config cfg) throws Exception {
        String type = cfg.type;
        long intervalMs = cfg.intervalMs > 0 ? cfg.intervalMs : ("wechat".equals(type) ? 3000 : 2000);
        Duration overall = Duration.ofSeconds(cfg.timeoutSec);

        // 1) 获取二维码
        System.out.println("[1/3] 正在获取 " + typeName(type) + " 登录二维码 ...");
        MixAuthClient client = new MixAuthClient(cfg.baseUrls);
        Json.Object qrResp = client.getQr(type);
        if (!qrResp.optBoolean("success", true)) {
            throw new IOException("获取二维码失败: " + Json.toPrettyJson(qrResp, 0));
        }
        Json.Object data = qrResp.optObject("data");
        if (data == null) {
            throw new IOException("接口返回缺少 data 字段: " + Json.toPrettyJson(qrResp, 0));
        }
        String qrId = data.optString("id", "");
        String qrCode = data.optString("qrcode", "");
        System.out.println("      服务端点: " + client.baseUrl());
        System.out.println("      二维码ID: " + (qrId.length() > 40 ? qrId.substring(0, 40) + "...(" + qrId.length() + "字符)" : qrId));

        // 2) 展示 / 保存二维码
        System.out.println("[2/3] 下载二维码图片 ...");
        byte[] png = client.downloadQrImage(qrCode);
        String savedPath = client.saveQrImage(png, type);
        System.out.println("      二维码已保存: " + savedPath);

        QrWindow window = null;
        if (cfg.gui) {
            try {
                window = new QrWindow(type);
                window.showQr(png);
                window.setStatus("请使用手机 " + ("qq".equals(type) ? "QQ" : "微信") + " 扫描二维码登录");
                System.out.println("      已弹出二维码窗口");
            } catch (Exception ex) {
                System.out.println("      图形窗口异常，自动降级为图片文件模式: " + ex.getMessage());
                window = null;
            }
        } else {
            System.out.println("      [无图形模式] 请打开图片文件并用手机 " + ("qq".equals(type) ? "QQ" : "微信") + " 扫码:");
            System.out.println("        " + savedPath);
        }

        // 3) 轮询登录状态
        System.out.println("[3/3] 开始轮询登录状态（每 " + intervalMs + "ms 一次，最长 " + cfg.timeoutSec + "s）");
        long deadline = System.currentTimeMillis() + overall.toMillis();
        String lastState = "";
        long lastTick = 0;

        while (System.currentTimeMillis() < deadline) {
            if (window != null && window.isClosed()) {
                System.out.println("[中止] 二维码窗口被关闭，登录取消");
                return 1;
            }

            Json.Object statusResp;
            try {
                statusResp = client.queryStatus(type, qrId);
            } catch (IOException ex) {
                // 微信通道较慢，网络抖动时静默重试
                if (System.currentTimeMillis() - lastTick >= 10000) {
                    System.out.println("      状态查询暂时失败，继续重试: " + ex.getMessage());
                    lastTick = System.currentTimeMillis();
                }
                Thread.sleep(intervalMs);
                continue;
            }

            Json.Object statusData = statusResp.optObject("data");
            if (statusData == null) {
                throw new IOException("状态接口返回缺少 data 字段: " + Json.toPrettyJson(statusResp, 0));
            }
            String signData = statusResp.optString("signData", "");

            State s = classify(type, statusData);
            if (!s.text.equals(lastState)) {
                System.out.println("      状态: " + s.text);
                if (window != null) {
                    window.setStatus(s.text);
                }
                lastState = s.text;
            }

            if (s.success) {
                return printLoginSuccess(type, statusData, signData, window);
            }
            if (s.expired) {
                System.out.println("[失败] " + s.text + "，请重新运行程序");
                if (window != null) {
                    window.setStatus("二维码已过期，程序结束");
                    Thread.sleep(1200);
                    window.close();
                }
                return 1;
            }

            Thread.sleep(intervalMs);
        }

        System.out.println("[超时] 在 " + cfg.timeoutSec + " 秒内未完成登录");
        if (window != null) {
            window.close();
        }
        return 1;
    }

    // ============================ 状态分类 ============================

    private static final class State {
        boolean success;
        boolean expired;
        String text;

        State(String text) {
            this.text = text;
        }
    }

    private State classify(String type, Json.Object d) {
        if ("qq".equals(type)) {
            String code = d.optString("code", "");
            String message = d.optString("message", "");
            String redirect = d.optString("redirect", "");
            String qq = d.optString("qq", "");
            boolean ok = d.optBoolean("success", false)
                    || "0".equals(code)
                    || !qq.isEmpty()
                    || !redirect.isEmpty();
            if (ok) {
                State s = new State("登录成功！");
                s.success = true;
                return s;
            }
            if ("65".equals(code) || message.contains("已失效") || message.contains("过期")) {
                State s = new State("二维码已失效");
                s.expired = true;
                return s;
            }
            // 66=未失效(等待扫码) 67=已扫码等待确认 等，直接展示官方中文提示
            return new State(message.isEmpty() ? ("等待扫码 (code=" + code + ")") : (message + " (code=" + code + ")"));
        }

        // wechat
        boolean ok = d.optBoolean("success", false)
                || !d.optString("wx_code", "").isEmpty()
                || d.has("UserIdStr")
                || d.optObject("data") != null;
        if (ok) {
            State s = new State("登录成功！");
            s.success = true;
            return s;
        }
        String err = d.optString("wx_errcode", "");
        switch (err) {
            case "404", "405" -> {
                return new State("等待扫码...");
            }
            case "402" -> {
                return new State("已扫码，请在手机上确认登录");
            }
            case "408" -> {
                State s = new State("二维码已过期");
                s.expired = true;
                return s;
            }
            case "201" -> {
                State s = new State("已在手机上确认，正在获取用户信息...");
                return s;
            }
            case "200", "403" -> {
                State s = new State("用户取消登录");
                return s;
            }
            default -> {
                return new State("未知状态 (wx_errcode=" + (err.isEmpty() ? "空" : err) + ")");
            }
        }
    }

    // ============================ 成功输出 ============================

    private int printLoginSuccess(String type, Json.Object data, String signData, QrWindow window) throws IOException {
        System.out.println();
        System.out.println("==================================================");
        System.out.println(" 登录成功！(" + typeName(type) + ")");
        System.out.println("==================================================");

        if ("qq".equals(type)) {
            System.out.println(" QQ号 : " + data.optString("qq", "?"));
            System.out.println(" 昵称 : " + data.optString("username", "?"));
            System.out.println(" 头像 : " + data.optString("avatar", "?"));
        } else {
            // 微信用户信息通常在 data.data 里，字段随来源平台变动，逐字段兜底提取
            Json.Object user = data.optObject("data");
            if (user == null) {
                user = data; // 有的版本直接平铺
            }
            printIfPresent(" 账号ID(UserIdStr)", user, "UserIdStr");
            printIfPresent(" 英文名", user, "EnglishName");
            printIfPresent(" 中文名", user, "ChineseName");
            printIfPresent(" 昵称", user, "NickName", "nickname", "username", "name");
            printIfPresent(" 手机号(脱敏)", user, "Mobile");
            printIfPresent(" 头像", user, "Photo", "avatar", "headimgurl", "HeadImageUrl");
            if (user == data) {
                System.out.println(" （未能从响应中识别微信用户字段，请查看保存的完整 JSON）");
            }
        }

        if (!signData.isEmpty()) {
            String brief = signData.length() > 60 ? signData.substring(0, 60) + "..." : signData;
            System.out.println(" 签名 : " + brief + "  [格式: 时间戳|Base64(登录结果)|HMAC，如需防伪造可用 POST /api/verify 校验]");
        }

        Path out = Path.of(System.getProperty("user.dir"), "login-result-" + type + ".json");
        Files.writeString(out, Json.toPrettyJson(data, 0), StandardCharsets.UTF_8);
        System.out.println(" 完整登录信息已保存: " + out.toAbsolutePath());

        if (window != null) {
            window.setStatus("登录成功！");
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            window.close();
        }
        return 0;
    }

    private static void printIfPresent(String label, Json.Object obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k)) {
                String v = obj.optString(k, "");
                if (!v.isEmpty()) {
                    System.out.println(label + " : " + v);
                    return;
                }
            }
        }
    }

    private static String typeName(String type) {
        return "wechat".equals(type) ? "微信" : "QQ";
    }

    // ============================ 参数解析 ============================

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();
        boolean sawType = false;

        for (String arg : args) {
            switch (arg) {
                case "qq", "wechat" -> {
                    if (!sawType) {
                        cfg.type = arg;
                        sawType = true;
                    }
                }
                case "--gui" -> {
                    cfg.gui = true;
                    cfg.guiSet = true;
                }
                case "--no-gui" -> {
                    cfg.gui = false;
                    cfg.guiSet = true;
                }
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        cfg.timeoutSec = Integer.parseInt(arg.substring("--timeout=".length()));
                    } else if (arg.startsWith("--interval=")) {
                        cfg.intervalMs = Long.parseLong(arg.substring("--interval=".length()));
                    } else if (arg.startsWith("--base-url=")) {
                        cfg.baseUrls = splitUrls(arg.substring("--base-url=".length()));
                    } else {
                        System.err.println("忽略未知参数: " + arg);
                    }
                }
            }
        }

        // 未显式指定 GUI 开关时：有图形环境默认弹窗，无图形环境默认文件模式
        if (!cfg.guiSet) {
            cfg.gui = !GraphicsEnvironment.isHeadless();
        }
        return cfg;
    }

    private static List<String> splitUrls(String s) {
        List<String> list = new ArrayList<>();
        for (String part : s.split("[,\\s]+")) {
            if (!part.isEmpty()) {
                list.add(part.endsWith("/") ? part.substring(0, part.length() - 1) : part);
            }
        }
        return list;
    }

    private static void printUsage() {
        System.out.println("""
                MixAuth 登录测试应用 (纯 Java)
                用法: java -cp out com.example.mixauth.MixAuthLoginApp [qq|wechat] [选项]
                选项:
                  --no-gui       无图形环境模式（只保存二维码图片，不弹窗）
                  --timeout=秒   整体最长轮询时间（默认 180）
                  --interval=毫秒 轮询间隔（默认 qq=2000 / wechat=3000）
                  --base-url=URL 指定 MixAuth 服务（可逗号分隔多个，默认自动选择公开实例）
                示例:
                  java -cp out com.example.mixauth.MixAuthLoginApp qq
                  java -cp out com.example.mixauth.MixAuthLoginApp wechat --no-gui --timeout=120
                """);
    }
}