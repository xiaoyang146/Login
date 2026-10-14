package com.example.mixauthlogin;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MixAuth 登录测试 APP（Android，纯 Java）。
 *
 * 流程：点 QQ/微信按钮 -> 获取二维码并显示 -> 轮询登录状态 -> 弹窗展示登录信息。
 */
public class MainActivity extends Activity {

    private final MixAuthClient client = new MixAuthClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView qrView;
    private TextView statusView;
    private AtomicBoolean running;   // 非 null 且 true 时继续轮询
    private String currentType = "qq";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        qrView = findViewById(R.id.qrView);
        statusView = findViewById(R.id.statusView);
        Button qqBtn = findViewById(R.id.qqBtn);
        Button wxBtn = findViewById(R.id.wxBtn);
        Button refreshBtn = findViewById(R.id.refreshBtn);

        qqBtn.setOnClickListener(v -> startLogin("qq"));
        wxBtn.setOnClickListener(v -> startLogin("wechat"));
        refreshBtn.setOnClickListener(v -> startLogin(currentType));
    }

    // ============================ 登录流程 ============================

    private void startLogin(String type) {
        currentType = type;
        abort();

        running = new AtomicBoolean(true);
        statusView.setText("正在获取 " + typeName(type) + " 登录二维码...");
        qrView.setImageBitmap(null);

        executor.execute(() -> {
            try {
                JSONObject qr = client.getQr(type);
                String id = qr.getString("id");
                String qrCode = qr.getString("qrcode");

                byte[] imgBytes = client.fetchQrImage(qrCode);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                Bitmap finalBmp = bmp;
                ui.post(() -> {
                    if (finalBmp != null) {
                        qrView.setImageBitmap(finalBmp);
                        statusView.setText("请使用手机 " + typeName(type) + " 扫描上方二维码登录");
                    } else {
                        statusView.setText("二维码解析失败，请点击重新生成");
                    }
                });
                poll(type, id);
            } catch (Exception e) {
                ui.post(() -> statusView.setText("获取二维码失败: " + msg(e)));
            }
        });
    }

    private void poll(String type, String id) {
        long interval = "wechat".equals(type) ? 3000L : 2000L;
        while (running != null && running.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running == null || !running.get()) {
                return;
            }
            try {
                JSONObject resp = client.status(type, id);
                JSONObject data = resp.optJSONObject("data");
                if (data == null) {
                    continue;
                }
                State st = classify(type, data);
                ui.post(() -> statusView.setText(st.text));
                if (st.successJson != null) {
                    abort();
                    final String payload = st.successJson;
                    ui.post(() -> showSuccess(type, payload));
                    return;
                }
                if (st.expired) {
                    abort();
                    return;
                }
            } catch (Exception e) {
                // 网络抖动：静默重试下一轮
            }
        }
    }

    // ============================ 状态分类 ============================

    private State classify(String type, JSONObject d) {
        if ("qq".equals(type)) {
            String code = d.optString("code", "");
            String message = d.optString("message", "");
            String redirect = d.optString("redirect", "");
            boolean ok = d.optBoolean("success", false)
                    || "0".equals(code)
                    || !redirect.isEmpty()
                    || !d.optString("qq", "").isEmpty();
            if (ok) {
                JSONObject info = new JSONObject();
                try {
                    info.put("QQ号", d.optString("qq", "?"));
                    info.put("昵称", d.optString("username", "?"));
                    info.put("头像", d.optString("avatar", "?"));
                } catch (Exception ignored) {
                }
                return State.success(toPretty(info, "登录成功（响应解析失败）"));
            }
            if ("65".equals(code) || message.contains("已失效") || message.contains("过期")) {
                return State.expired("二维码已失效，请点击重新生成");
            }
            return new State(message.isEmpty() ? ("等待扫码... (code=" + code + ")") : message);
        }

        // 微信
        boolean ok = d.optBoolean("success", false)
                || !d.optString("wx_code", "").isEmpty()
                || d.has("UserIdStr");
        if (ok) {
            JSONObject user = d.optJSONObject("data") != null ? d.optJSONObject("data") : d;
            JSONObject info = new JSONObject();
            String[] keys = {"UserIdStr", "EnglishName", "ChineseName", "NickName",
                    "nickname", "Mobile", "Photo", "avatar", "headimgurl"};
            try {
                for (String k : keys) {
                    if (user.has(k)) {
                        info.put(k, user.opt(k));
                    }
                }
            } catch (Exception ignored) {
            }
            return State.success(toPretty(info, "登录成功（响应中暂无用户字段）"));
        }
        String err = d.optString("wx_errcode", "");
        switch (err) {
            case "404":
            case "405":
                return new State("等待扫码...");
            case "402":
                return new State("已扫码，请在手机上确认登录");
            case "408":
                return State.expired("二维码已过期，请点击重新生成");
            case "201":
                return new State("已在手机上确认，正在获取用户信息...");
            case "200":
            case "403":
                return new State("用户已取消登录");
            default:
                return new State("状态: wx_errcode=" + (err.isEmpty() ? "(空)" : err));
        }
    }

    // ============================ UI 辅助 ============================

    private void showSuccess(String type, String payload) {
        new AlertDialog.Builder(this)
                .setTitle("登录成功！(" + typeName(type) + ")")
                .setMessage(payload)
                .setPositiveButton("好的", null)
                .show();
    }

    private void abort() {
        if (running != null) {
            running.set(false);
        }
    }

    private static String typeName(String type) {
        return "wechat".equals(type) ? "微信" : "QQ";
    }

    /** JSONObject 美化输出，解析异常时返回兜底文案。 */
    private static String toPretty(JSONObject obj, String fallback) {
        if (obj.length() == 0) {
            return fallback;
        }
        try {
            return obj.toString(2);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        abort();
        executor.shutdownNow();
    }

    // ============================ 内部类型 ============================

    private static final class State {
        final String text;
        final String successJson;
        final boolean expired;

        State(String text) {
            this(text, null, false);
        }

        private State(String text, String successJson, boolean expired) {
            this.text = text;
            this.successJson = successJson;
            this.expired = expired;
        }

        static State success(String json) {
            return new State("登录成功！", json, false);
        }

        static State expired(String text) {
            return new State(text, null, true);
        }
    }
}