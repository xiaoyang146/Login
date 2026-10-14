package com.example.mixauthlogin;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Map;

/**
 * QQ互联官方登录 Activity（纯 Java）。
 *
 * 在 WebView 内打开 QQ 授权页（扫码 / 快捷登录 / 账号密码均可），
 * 授权成功后拦截回调 URL 拿 code，再走 code -> access_token -> openid -> 用户信息，
 * 结果通过 setResult 返回给 MainActivity。
 */
public class QqOAuthActivity extends Activity {

    private static final String TAG = "QqOAuth";

    private WebView web;
    private ProgressDialog dialog;
    private volatile boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(QqLogin.CALLBACK_PREFIX)) {
                    return handleRedirect(url);
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                dismissDialog();
            }
        });

        web.loadUrl(QqLogin.authorizeUrl(QqLogin.newState()));
        dialog = ProgressDialog.show(this, "QQ 登录", "正在加载授权页，请登录并授权...", true, false);
    }

    /** 拦截回调 URL：拿到 code 后异步换取用户信息。 */
    private boolean handleRedirect(String url) {
        if (finished) {
            return true;
        }
        Map<String, String> q = QqLogin.parseQuery(url);
        String code = q.get("code");
        if (code == null || code.isEmpty()) {
            finished = true;
            String err = q.get("error_description") != null
                    ? q.get("error_description")
                    : q.get("error");
            Toast.makeText(this, "QQ 授权失败: " + (err == null ? url : err), Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED, new Intent().putExtra("error",
                    err == null ? url : err));
            finish();
            return true;
        }

        finished = true;
        dialog = ProgressDialog.show(this, "QQ 登录", "已授权，正在获取用户信息...", true, false);

        new Thread(() -> {
            JSONObject info = null;
            String err = null;
            try {
                info = new QqLogin().exchange(code);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final JSONObject fi = info;
            final String fe = err;
            runOnUiThread(() -> {
                dismissDialog();
                if (fi != null) {
                    Intent data = new Intent();
                    try {
                        data.putExtra("info", fi.toString(2));
                    } catch (Exception ex) {
                        data.putExtra("info", String.valueOf(fi));
                    }
                    setResult(RESULT_OK, data);
                } else {
                    setResult(RESULT_CANCELED,
                            new Intent().putExtra("error", fe));
                }
                finish();
            });
        }).start();
        return true;
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDialog();
        if (web != null) {
            web.destroy();
        }
    }
}