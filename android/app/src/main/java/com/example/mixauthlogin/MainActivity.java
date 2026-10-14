package com.example.mixauthlogin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登录测试主界面（Android，纯 Java）。
 *
 * <p>三条登录通道：
 * <ul>
 *   <li><b>手机 QQ 一键登录</b>：腾讯官方 OpenSDK，装了 QQ 直接 SSO 免密授权（推荐）；</li>
 *   <li><b>微信扫码</b>：MixAuth HTTP 接口，App 内展示二维码；</li>
 *   <li>旧的 WebView 授权页已从主入口移除（其 redirect_uri 用自定义 scheme，WebView 永远拦不到回调）。</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private final MixAuthClient client = new MixAuthClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView qrView;
    private ImageView avatarView;
    private TextView statusView;
    private TextView nickView;
    private TextView openIdView;
    private TextView tokenView;
    private CheckBox consentCheck;
    private Button qqLoginBtn;
    private Button wxLoginBtn;
    private Button refreshBtn;
    private View loginPanel;
    private View profilePanel;

    private AtomicBoolean running;
    private String currentType = "qq";
    private QqSdkAuth qq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loginPanel = findViewById(R.id.loginPanel);
        profilePanel = findViewById(R.id.profilePanel);
        qrView = findViewById(R.id.qrView);
        avatarView = findViewById(R.id.avatarView);
        statusView = findViewById(R.id.statusView);
        nickView = findViewById(R.id.nickView);
        openIdView = findViewById(R.id.openIdView);
        tokenView = findViewById(R.id.tokenView);
        consentCheck = findViewById(R.id.consentCheck);
        qqLoginBtn = findViewById(R.id.qqLoginBtn);
        wxLoginBtn = findViewById(R.id.wxLoginBtn);
        refreshBtn = findViewById(R.id.refreshBtn);

        consentCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                onConsentChanged(isChecked);
            }
        });

        qqLoginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doQqSdkLogin();
            }
        });

        wxLoginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQrLogin("wechat");
            }
        });

        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQrLogin(currentType);
            }
        });

        // 如果用户之前已经同意过隐私政策（本地记录），直接放开按钮
        boolean agreed = getPreferences(MODE_PRIVATE).getBoolean("privacy_agreed", false);
        consentCheck.setChecked(agreed);
        if (agreed) {
            onConsentChanged(true);
        } else {
            statusView.setText(R.string.status_idle);
        }
    }

    // ======================= 隐私合规 =======================

    /**
     * 只有在用户同意之后才允许初始化 SDK 与放开按钮。
     * 这是 QQ互联 SDK 3.5.7+ 的硬性要求。
     */
    private void onConsentChanged(boolean granted) {
        qqLoginBtn.setEnabled(granted);
        wxLoginBtn.setEnabled(granted);
        if (granted) {
            QqSdkAuth.grantPermission();
            getPreferences(MODE_PRIVATE).edit().putBoolean("privacy_agreed", true).apply();
            qq = QqSdkAuth.get(getApplicationContext());
            if (!qq.isQQInstalled()) {
                statusView.setText(R.string.qq_not_installed);
            } else {
                statusView.setText(R.string.status_idle);
            }
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("privacy_agreed", false).apply();
            statusView.setText("请先勾选同意隐私政策");
        }
    }

    // ======================= 通道一：官方 SDK =======================

    private void doQqSdkLogin() {
        if (qq == null) {
            qq = QqSdkAuth.get(getApplicationContext());
        }
        statusView.setText("正在拉起手机 QQ 授权…");
        qq.login(this, new QqSdkAuth.Callback() {
            @Override
            public void onSuccess(QqSdkAuth.Session session, QqSdkAuth.Profile cached) {
                showSession(session, cached);
                fetchQqProfile();
            }

            @Override
            public void onFailure(int errorCode, String message, boolean cancelled) {
                statusView.setText(cancelled ? "已取消 QQ 授权" : ("QQ 登录失败：" + message));
                if (!cancelled) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("QQ 登录失败")
                            .setMessage(message)
                            .setPositiveButton("知道了", null)
                            .show();
                }
            }
        });
    }

    private void fetchQqProfile() {
        qq.fetchProfile(MainActivity.this, new QqSdkAuth.ProfileCallback() {
            @Override
            public void onProfile(final QqSdkAuth.Profile profile) {
                nickView.setText(profile.nickname);
                if (!TextUtils.isEmpty(profile.avatarUrl)) {
                    loadAvatar(profile.avatarUrl);
                }
            }

            @Override
            public void onError(final int code, final String message) {
                Log.w(TAG, "fetchProfile 失败: " + code + " / " + message);
                // 不能让界面一直停在「正在获取昵称…」，把失败原因显式暴露出来
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        nickView.setText("昵称获取失败（点击重试）");
                        nickView.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                nickView.setText("（正在获取昵称…）");
                                fetchQqProfile();
                            }
                        });
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("获取昵称失败")
                                .setMessage("错误码 " + code + "\n" + message
                                        + "\n\n登录本身是成功的，openid 与 token 已拿到，"
                                        + "只是 get_simple_userinfo 接口没返回昵称。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        });
    }

    private void showSession(QqSdkAuth.Session session, QqSdkAuth.Profile cached) {
        loginPanel.setVisibility(View.GONE);
        profilePanel.setVisibility(View.VISIBLE);
        qrView.setImageBitmap(null);

        openIdView.setText("openid: " + session.openId);
        tokenView.setText("openid : " + session.openId + "\n"
                + "token  : " + mask(session.accessToken) + "\n"
                + "有效期至: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(session.expireAtMillis)));
        if (cached != null && !TextUtils.isEmpty(cached.nickname)) {
            nickView.setText(cached.nickname);
        } else {
            nickView.setText("（正在获取昵称…）");
        }
        statusView.setText("登录成功");

        Button logoutBtn = findViewById(R.id.logoutBtn);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogout();
            }
        });
    }

    private void doLogout() {
        if (qq != null) {
            qq.logout(this);
        }
        abort();
        profilePanel.setVisibility(View.GONE);
        loginPanel.setVisibility(View.VISIBLE);
        qrView.setImageBitmap(null);
        avatarView.setImageBitmap(null);
        nickView.setText("");
        openIdView.setText("");
        tokenView.setText("");
        statusView.setText("已退出登录");
    }

    private void loadAvatar(final String url) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    InputStream is = c.getInputStream();
                    bmp = BitmapFactory.decodeStream(is);
                    is.close();
                } catch (Exception e) {
                    Log.w(TAG, "头像下载失败", e);
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
                final Bitmap fb = bmp;
                if (fb != null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            avatarView.setImageBitmap(fb);
                        }
                    });
                }
            }
        });
    }

    // ======================= 通道二：MixAuth 扫码 =======================

    private void startQrLogin(String type) {
        currentType = type;
        abort();

        running = new AtomicBoolean(true);
        statusView.setText("正在获取 " + typeName(type) + " 登录二维码…");
        qrView.setImageBitmap(null);
        refreshBtn.setVisibility(View.GONE);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject qr = client.getQr(currentType);
                    final String id = qr.getString("id");
                    String qrCode = qr.getString("qrcode");

                    byte[] imgBytes = client.fetchQrImage(qrCode);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (bmp != null) {
                                qrView.setImageBitmap(bmp);
                                statusView.setText("请使用手机 " + typeName(currentType)
                                        + " 扫描上方二维码登录");
                            } else {
                                statusView.setText("二维码解析失败，请点击重新生成");
                                refreshBtn.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                    poll(currentType, id);
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("获取二维码失败: " + msg(e));
                            refreshBtn.setVisibility(View.VISIBLE);
                        }
                    });
                }
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
                final State st = classify(type, data);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText(st.text);
                    }
                });
                if (st.successJson != null) {
                    abort();
                    final String payload = st.successJson;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showSuccess(type, payload);
                        }
                    });
                    return;
                }
                if (st.expired) {
                    abort();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshBtn.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                // 网络抖动：静默重试下一轮
            }
        }
    }

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

    private void showSuccess(String type, String payload) {
        new AlertDialog.Builder(this)
                .setTitle("登录成功！(" + typeName(type) + ")")
                .setMessage(payload)
                .setPositiveButton("好的", null)
                .show();
    }

    // ======================= 生命周期 / 工具 =======================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 关键：必须转发给 SDK，否则永远收不到 QQ 授权结果
        if (qq != null) {
            qq.onActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 日志辅助：部分机型从 QQ 返回时会复用 Activity，这里记录一下方便排查
        Log.i(TAG, "onNewIntent: " + (intent == null ? "null" : intent.getDataString()));
    }

    private void abort() {
        if (running != null) {
            running.set(false);
        }
    }

    private static String typeName(String type) {
        return "wechat".equals(type) ? "微信" : "QQ";
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 12) {
            return String.valueOf(token);
        }
        return token.substring(0, 8) + "…" + token.substring(token.length() - 4)
                + " (len=" + token.length() + ")";
    }

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

    /** 便于排查：把 Toast 与日志一起用，部分 ROM 会吞 Toast。 */
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        Log.i(TAG, s);
    }
}
