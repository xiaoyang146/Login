package com.example.mixauthlogin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.connect.UserInfo;
import com.tencent.connect.common.Constants;
import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;
import com.tencent.tauth.UiError;

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

    /** QQ 互联官方 SDK 实例（SSO 直连手机 QQ 授权）。 */
    private Tencent tencent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 QQ 互联 SDK
        try {
            tencent = Tencent.createInstance(QqLogin.APP_ID, getApplicationContext());
        } catch (Exception e) {
            tencent = null;
        }

        qrView = findViewById(R.id.qrView);
        statusView = findViewById(R.id.statusView);
        Button qqBtn = findViewById(R.id.qqBtn);
        Button wxBtn = findViewById(R.id.wxBtn);
        Button refreshBtn = findViewById(R.id.refreshBtn);

        qqBtn.setOnClickListener(v -> startQqLogin());
        wxBtn.setOnClickListener(v -> startLogin("wechat"));
        refreshBtn.setOnClickListener(v -> startLogin(currentType));
    }

    // ============================ 登录流程 ============================

    // ============================ QQ 官方互联 SSO ============================

    /**
     * QQ 登录入口：优先调起手机 QQ 客户端完成授权（SSO 一键登录）；
     * 未安装手机 QQ 或环境不支持时，回退到内置 WebView 授权页（QqOAuthActivity）。
     */
    private void startQqLogin() {
        statusView.setText("正在拉起 QQ 授权...");
        if (tencent != null && tencent.isSupportSSOLogin(this)) {
            tencent.login(this, QqLogin.SCOPE, ssoListener);
        } else {
            startActivityForResult(new Intent(MainActivity.this, QqOAuthActivity.class), 100);
        }
    }

    /** SSO 授权结果回调（SDK 保证在主线程回调）。 */
    private final IUiListener ssoListener = new IUiListener() {
        @Override
        public void onComplete(Object response) {
            if (!(response instanceof JSONObject)) {
                statusView.setText("QQ 授权回调数据异常");
                return;
            }
            JSONObject resp = (JSONObject) response;
            String openid = resp.optString("openid", "");
            String accessToken = resp.optString("access_token", "");
            statusView.setText("QQ 授权成功，正在获取用户资料...");
            requestSsoUserInfo(openid, accessToken);
        }

        @Override
        public void onError(UiError e) {
            String m = e == null ? "未知错误"
                    : (e.errorMessage + " (code=" + e.errorCode + ", detail=" + e.errorDetail + ")");
            statusView.setText("QQ 登录失败: " + m);
            Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onCancel() {
            statusView.setText("QQ 登录已取消");
        }

        @Override
        public void onWarning(int i) {
            // SDK 预留的警告回调，登录主流程无需处理
        }
    };

    /** SSO 拿到 access_token/openid 后，通过 SDK 补齐昵称、头像等用户资料。 */
    private void requestSsoUserInfo(final String openid, final String accessToken) {
        try {
            new UserInfo(this, tencent.getQQToken()).getUserInfo(new IUiListener() {
                @Override
                public void onComplete(Object o) {
                    JSONObject info = new JSONObject();
                    try {
                        info.put("openid", openid);
                        if (o instanceof JSONObject) {
                            JSONObject u = (JSONObject) o;
                            info.put("ret", u.optInt("ret", -1));
                            if (u.has("nickname")) {
                                info.put("nickname", u.optString("nickname"));
                            }
                            if (u.has("figureurl_qq_2")) {
                                info.put("figureurl_qq_2", u.optString("figureurl_qq_2"));
                            }
                        }
                        info.put("access_token", accessToken != null && accessToken.length() > 12
                                ? accessToken.substring(0, 12) + "..." : accessToken);
                    } catch (Exception ignored) {
                    }
                    showSuccess("QQ（官方互联·APP 授权）",
                            toPretty(info, "SSO 授权成功，用户资料未返回"));
                }

                @Override
                public void onError(UiError e) {
                    showSuccess("QQ（官方互联·APP 授权）",
                            "openid=" + openid + "\n获取用户资料失败: "
                                    + (e == null ? "未知错误" : e.errorMessage));
                }

                @Override
                public void onCancel() {
                    statusView.setText("获取 QQ 用户资料被取消");
                }

                @Override
                public void onWarning(int i) {
                    // 忽略 SDK 警告回调
                }
            });
        } catch (Exception e) {
            statusView.setText("QQ 用户资料请求异常: " + msg(e));
        }
    }

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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // QQ 互联 SDK 的 SSO 跳转回传
        if (requestCode == Constants.REQUEST_LOGIN || requestCode == Constants.REQUEST_APPBAR) {
            Tencent.onActivityResultData(requestCode, resultCode, data, ssoListener);
            return;
        }
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                showSuccess("QQ（官方互联）", data.getStringExtra("info"));
            } else {
                String err = data != null ? data.getStringExtra("error") : "用户取消";
                statusView.setText("QQ 登录失败: " + (err == null ? "未知错误" : err));
            }
        }
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