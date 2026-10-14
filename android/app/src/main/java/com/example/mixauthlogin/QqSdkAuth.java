package com.example.mixauthlogin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.connect.UserInfo;
import com.tencent.connect.common.Constants;
import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;
import com.tencent.tauth.UiError;

import org.json.JSONObject;

/**
 * 手机 QQ 快捷登录（腾讯官方 QQ 互联 Android SDK 3.5.19 封装，纯 Java）。
 *
 * <p>与旧的 {@link QqOAuthActivity}（WebView 打开 graph.qq.com 授权页）不同，
 * 本类走的是官方 <b>SSO</b> 通道：手机装了 QQ 时直接拉起 QQ 客户端免密授权，
 * 没装 QQ 时 SDK 内部自动降级为它自带的 H5 授权页。这也是「调用手机 QQ 进行登录」
 * 唯一受官方支持的做法。
 *
 * <h3>上线前必须核对的三件事（否则必然失败）</h3>
 * <ol>
 *   <li>QQ互联后台（connect.qq.com）该应用的 <b>包名</b> 必须等于
 *       {@link BuildConfig#APPLICATION_ID}，即 {@code com.android.qileys}；</li>
 *   <li>后台登记的 <b>应用签名 MD5</b> 必须等于当前 APK 签名证书的 MD5（32 位小写、无冒号）；
 *       本工程签名固定使用仓库里的 {@code android/qileys.keystore}，所以 MD5 是稳定的，
 *       可用 {@code keytool -printcert -jarfile app-debug.apk} 或 CI 日志中的
 *       "APK signing certificate" 一行获取；</li>
 *   <li>用户必须在同意隐私政策之后才允许调用 SDK（见 {@link #grantPermission}）。</li>
 * </ol>
 *
 * <h3>为什么不能自己拼 Intent 拉起 QQ</h3>
 * QQ 客户端的授权 Activity 用的是未公开的 scheme（mqqopensdkapi 等），
 * 版本一变就失效且不合规，必须走官方 SDK。
 */
public final class QqSdkAuth {

    private static final String TAG = "QqSdkAuth";

    private static final String PREFS = "qq_sdk_session";
    private static final String K_OPENID = "openid";
    private static final String K_TOKEN = "access_token";
    private static final String K_EXPIRE = "expire_at";
    private static final String K_NICK = "nickname";
    private static final String K_AVATAR = "avatar";
    private static final String K_GENDER = "gender";

    /** 申请的权限，逗号分隔。只需要基础资料就写 "get_user_info"，全量可写 "all"。 */
    private static final String SCOPE = "get_user_info";

    /**
     * QQ 的 user/get_simple_userinfo 对新登录会话存在服务端同步延迟，
     * 表现为 ret=0 但响应里没有 nickname。失败后延迟重试。
     */
    private static final int MAX_PROFILE_RETRY = 3;
    private static final long PROFILE_RETRY_DELAY_MS = 1200L;

    /** 重试用的主线程 Handler。 */
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 本类实例（成员变量持有，SDK 内部对 listener 是弱引用，不能临时 new）。 */
    private static volatile QqSdkAuth sInstance;

    private final Tencent tencent;
    private final SharedPreferences prefs;
    private final Context appContext;

    /** 强引用，防止用户在 QQ 授权页停留过久导致回调被 GC。 */
    private IUiListener loginListener;
    /** 同上，UserInfo 的 listener 也是弱引用。 */
    private UserInfo userInfo;

    private Callback pendingCallback;

    // ------------------------------------------------------------------ 回调接口

    public interface Callback {
        /** 登录成功。{@code profile} 可能为 null（用户信息还没拉到）。 */
        void onSuccess(Session session, Profile profile);

        /** @param cancelled 用户主动取消授权 */
        void onFailure(int errorCode, String message, boolean cancelled);
    }

    /** 持久化的登录态。 */
    public static final class Session {
        public final String openId;
        public final String accessToken;
        public final long expireAtMillis;

        Session(String openId, String accessToken, long expireAtMillis) {
            this.openId = openId;
            this.accessToken = accessToken;
            this.expireAtMillis = expireAtMillis;
        }

        public boolean isUsable() {
            return !TextUtils.isEmpty(openId)
                    && !TextUtils.isEmpty(accessToken)
                    && System.currentTimeMillis() < expireAtMillis;
        }
    }

    /** 昵称/头像等基础资料。 */
    public static final class Profile {
        public final String nickname;
        public final String avatarUrl;
        public final String gender;

        Profile(String nickname, String avatarUrl, String gender) {
            this.nickname = nickname;
            this.avatarUrl = avatarUrl;
            this.gender = gender;
        }
    }

    // ------------------------------------------------------------------ 单例 / 初始化

    private QqSdkAuth(Context appContext) {
        this.appContext = appContext.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // 第三个参数是与 AndroidManifest 中 FileProvider 的 authorities 一致的值。
        // 3.5.19 里官方期望的就是 Constants.FILE_PROVIDER_AUTHORITIES。
        this.tencent = Tencent.createInstance(
                BuildConfig.QQ_APP_ID, this.appContext, Constants.FILE_PROVIDER_AUTHORITIES);
        Log.i(TAG, "createInstance ok, appId=" + BuildConfig.QQ_APP_ID
                + ", authorities=" + Constants.FILE_PROVIDER_AUTHORITIES
                + ", permissionGranted=" + !Tencent.isPermissionNotGranted());
    }

    /**
     * 必须在<b>用户同意隐私政策之后</b>调用，否则 SDK 所有接口都会直接失败。
     * SDK 3.5.7 起强制要求，这是合规红线。
     */
    public static void grantPermission() {
        Tencent.setIsPermissionGranted(true);
        Log.i(TAG, "setIsPermissionGranted(true)");
    }

    /** 取得实例（首次调用前请确保已 {@link #grantPermission()}）。 */
    public static synchronized QqSdkAuth get(Context context) {
        if (sInstance == null) {
            sInstance = new QqSdkAuth(context);
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 发起 QQ 登录。
     *
     * @param activity 调用方 Activity（SDK 会从它跳到 QQ 授权页）
     */
    public void login(Activity activity, Callback callback) {
        this.pendingCallback = callback;
        this.loginListener = new LoginListener();

        // 本次启动如果本地有登录态，可直接复用，免去一次跳转
        Session cached = loadSession();
        if (cached != null && cached.isUsable()) {
            tencent.setOpenId(cached.openId);
            tencent.setAccessToken(cached.accessToken,
                    String.valueOf(Math.max(0, (cached.expireAtMillis - System.currentTimeMillis()) / 1000L)));
        }

        int code = tencent.login(activity, SCOPE, loginListener);
        Log.i(TAG, "tencent.login() -> requestCode=" + code
                + ", isQQInstalled=" + isQQInstalled());
    }

    /**
     * 静默校验登录态：先用本地缓存的 openId/accessToken set 回去再调用 login，
     * token 有效时立刻回调成功，失效时 SDK 会自动进入正常登录流程。
     */
    public void silentLogin(Activity activity, Callback callback) {
        this.pendingCallback = callback;
        this.loginListener = new LoginListener();

        Session cached = loadSession();
        if (cached != null) {
            tencent.setOpenId(cached.openId);
            tencent.setAccessToken(cached.accessToken,
                    String.valueOf(Math.max(0, (cached.expireAtMillis - System.currentTimeMillis()) / 1000L)));
        }
        tencent.login(activity, SCOPE, loginListener);
    }

    /**
     * 必须在宿主 Activity 的 {@code onActivityResult} 中转发，否则永远收不到回调。
     *
     * @return 是否已消费该结果
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (loginListener == null) {
            return false;
        }
        // 3.5.19 的 Tencent 没有实例方法 onActivityResult，用官方静态入口。
        boolean handled = Tencent.onActivityResultData(requestCode, resultCode, data, loginListener);
        Log.i(TAG, "onActivityResult req=" + requestCode + " res=" + resultCode + " handled=" + handled);
        return handled;
    }

    /**
     * 注销：清掉 SDK 内部与本地登录态。
     *
     * <p>注意顺序：先清 SDK 的会话，再清本地缓存，避免切换账号时
     * 旧缓存残留被后续 {@code fetchProfile} 误用。
     */
    public void logout(Context context) {
        try {
            // 清掉 SDK 内部持有的 openid/token，防止下一个账号登录时串用
            tencent.setOpenId(null);
            tencent.setAccessToken(null, null);
        } catch (Throwable t) {
            Log.w(TAG, "reset tencent session failed", t);
        }
        try {
            tencent.logout(context);
        } catch (Throwable t) {
            Log.w(TAG, "logout failed", t);
        }
        // 彻底清掉本地缓存（含上个账号的昵称/头像）
        prefs.edit().clear().apply();
        Log.i(TAG, "logout: SDK 会话与本地缓存均已清空");
    }

    /** QQ 是否已安装（3.5.9+ 结果会被内存缓存）。 */
    public boolean isQQInstalled() {
        return tencent.isQQInstalled(appContext);
    }

    /**
     * 用户在本次运行期间才装好 QQ 时，需要重置 SDK 的安装状态缓存，
     * 否则它一直认为"未安装"而走 H5 降级。
     */
    public static void resetQQInstalledCache() {
        Tencent.resetTargetAppInfoCache();
    }

    // ------------------------------------------------------------------ 用户信息

    /**
     * 拉取昵称头像（异步，回调在主线程）。
     *
     * <p>这里做了三件事来解决「换个 QQ 号就取不到昵称」的问题：
     * <ol>
     *   <li><b>不再用本地缓存覆盖 SDK 实例</b>：旧实现无条件 {@code loadSession()} 后
     *       {@code setOpenId/setAccessToken}，切换账号时会把刚登录成功的新 token
     *       覆盖成上一个账号的，导致请求必然失败；</li>
     *   <li><b>首次失败自动重试</b>：QQ 的 {@code user/get_simple_userinfo} 对新登录会话
     *       存在服务端同步延迟，返回的 JSON 里可能没有 nickname。因此失败后延迟重试，
     *       而不是直接报错；</li>
     *   <li><b>明确区分"接口错误"与"数据未就绪"</b>，只对后者重试。</li>
     * </ol>
     */
    public void fetchProfile(Context context, final ProfileCallback callback) {
        fetchProfileInternal(context, callback, 0);
    }

    /**
     * 带显式会话的拉取：登录成功后应立即用刚拿到的 session 调这个重载，
     * 避免 {@link #fetchProfile} 依赖本地缓存而拿到上一个账号的 openid。
     *
     * @param session 刚登录成功拿到的会话（非 null 时会重新绑定到 SDK 实例）
     */
    public void fetchProfile(Context context, Session session, ProfileCallback callback) {
        if (session != null && session.isUsable()) {
            tencent.setOpenId(session.openId);
            tencent.setAccessToken(session.accessToken, String.valueOf(Math.max(0,
                    (session.expireAtMillis - System.currentTimeMillis()) / 1000L)));
            Log.i(TAG, "fetchProfile: 使用登录回调中的会话，openid=" + session.openId);
        }
        fetchProfileInternal(context, callback, 0);
    }

    /** @param attempt 已重试次数（0 = 首次） */
    private void fetchProfileInternal(final Context context, final ProfileCallback callback,
                                      final int attempt) {
        userInfo = new UserInfo(context, tencent.getQQToken());
        userInfo.getUserInfo(new IUiListener() {
            @Override
            public void onComplete(Object response) {
                JSONObject json = asJson(response);
                Log.i(TAG, "getUserInfo onComplete (attempt=" + attempt + "): " + json);

                // QQ 把业务错误也放在 200 响应体里（ret!=0），必须自己判，否则会静默失败
                int ret = json.optInt("ret", -1);
                if (ret != 0) {
                    // ret!=0 属于真正的接口错误，重试无意义
                    callback.onError(ret, describe(ret) + " [ret=" + ret + "] "
                            + json.optString("msg", "") + " | " + json);
                    return;
                }

                String nickname = json.optString("nickname", "");
                String avatar = firstNonEmpty(
                        json.optString("figureurl_qq_2", ""),
                        json.optString("figureurl_qq_1", ""),
                        json.optString("figureurl_2", ""));
                String gender = json.optString("gender", "");

                if (TextUtils.isEmpty(nickname)) {
                    // ret==0 但没有昵称：典型的"服务端资料尚未同步完成"，重试大概率能拿到
                    if (attempt < MAX_PROFILE_RETRY) {
                        Log.w(TAG, "ret=0 但无 nickname，第 " + (attempt + 1)
                                + " 次重试（延迟 " + PROFILE_RETRY_DELAY_MS + "ms）");
                        uiHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                fetchProfileInternal(context, callback, attempt + 1);
                            }
                        }, PROFILE_RETRY_DELAY_MS);
                        return;
                    }
                    callback.onError(-1, "接口返回 ret=0 但连续 "
                            + (MAX_PROFILE_RETRY + 1) + " 次都没有昵称字段。"
                            + "该 QQ 号可能未完善资料，或服务端仍在同步。原始响应：" + json);
                    return;
                }

                prefs.edit().putString(K_NICK, nickname).putString(K_AVATAR, avatar)
                        .putString(K_GENDER, gender).apply();
                callback.onProfile(new Profile(nickname, avatar, gender));
            }

            @Override
            public void onError(UiError e) {
                Log.e(TAG, "getUserInfo onError: " + e.errorCode + " / " + e.errorMessage);
                callback.onError(e.errorCode, describe(e.errorCode) + "\n[" + e.errorCode + "] "
                        + e.errorMessage + "\n" + e.errorDetail);
            }

            @Override
            public void onCancel() {
                callback.onError(-1, "已取消");
            }

            @Override
            public void onWarning(int code) {
                // 官方 SDK 3.5.19 新增，忽略即可
            }
        });
    }

    public interface ProfileCallback {
        void onProfile(Profile profile);

        void onError(int code, String message);
    }

    // ------------------------------------------------------------------ 登录态持久化

    public Session loadSession() {
        String openId = prefs.getString(K_OPENID, null);
        String token = prefs.getString(K_TOKEN, null);
        long expire = prefs.getLong(K_EXPIRE, 0L);
        if (TextUtils.isEmpty(openId) || TextUtils.isEmpty(token)) {
            return null;
        }
        return new Session(openId, token, expire);
    }

    /** 读取上次缓存的基础资料，离线也能展示。 */
    public Profile loadCachedProfile() {
        String nick = prefs.getString(K_NICK, null);
        if (TextUtils.isEmpty(nick)) {
            return null;
        }
        return new Profile(nick, prefs.getString(K_AVATAR, ""), prefs.getString(K_GENDER, ""));
    }

    private void saveSession(Session s) {
        prefs.edit()
                .putString(K_OPENID, s.openId)
                .putString(K_TOKEN, s.accessToken)
                .putLong(K_EXPIRE, s.expireAtMillis)
                .apply();
    }

    // ------------------------------------------------------------------ 内部实现

    private final class LoginListener implements IUiListener {

        @Override
        public void onComplete(Object response) {
            JSONObject json = asJson(response);
            Log.i(TAG, "login onComplete: " + json);
            int ret = json.optInt("ret", -1);
            if (ret != 0) {
                fail(ret, "登录失败: " + json.optString("msg", "") + " (ret=" + ret + ")", false);
                return;
            }
            String openId = json.optString("openid", "");
            String token = json.optString("access_token", "");
            long expiresIn = parseLong(json.optString("expires_in", "0"), 0L);
            if (TextUtils.isEmpty(openId) || TextUtils.isEmpty(token)) {
                fail(-1, "登录返回缺少 openid/access_token: " + json, false);
                return;
            }
            long expireAt = System.currentTimeMillis() + expiresIn * 1000L;
            Session session = new Session(openId, token, expireAt);
            saveSession(session);

            // 关键：把登录态绑回 SDK 实例。
            // 否则后续 UserInfo/requestAsync 可能拿着空 token 去请求，返回 ret!=0（表现为拿不到昵称）。
            tencent.setOpenId(openId);
            tencent.setAccessToken(token, String.valueOf(Math.max(0, expiresIn)));
            Log.i(TAG, "session bound to Tencent instance, openid=" + openId
                    + ", expiresIn=" + expiresIn + "s");

            Callback cb = pendingCallback;
            if (cb != null) {
                // 先回调成功，资料由调用方决定是否再拉
                cb.onSuccess(session, loadCachedProfile());
            }
        }

        @Override
        public void onError(UiError e) {
            Log.e(TAG, "login onError code=" + e.errorCode
                    + " msg=" + e.errorMessage + " detail=" + e.errorDetail);
            fail(e.errorCode, describe(e.errorCode) + "\n[" + e.errorCode + "] "
                    + e.errorMessage + "\n" + e.errorDetail, false);
        }

        @Override
        public void onCancel() {
            Log.w(TAG, "login onCancel");
            fail(-1, "用户取消了 QQ 授权", true);
        }

        @Override
        public void onWarning(int code) {
            Log.w(TAG, "login onWarning code=" + code);
        }
    }

    private void fail(int code, String message, boolean cancelled) {
        Callback cb = pendingCallback;
        if (cb != null) {
            cb.onFailure(code, message, cancelled);
        }
    }

    /** 把 QQ互联常见的错误码翻译成人话，省得对着日志猜。 */
    static String describe(int code) {
        switch (code) {
            case 100044:
                return "【签名校验失败】QQ互联后台登记的包名/签名 MD5 与当前 APK 不一致。"
                        + "请用 keytool 取出本 APK 签名 MD5 后填到 connect.qq.com。";
            case 100030:
                return "【应用未通过审核 / 无该接口权限】";
            case 100016:
                return "【access_token 校验失败，可能已过期】";
            case 100032:
                return "【授权失败或用户未授权】";
            case 100026:
                return "【参数错误，检查 appId 与 scope】";
            case 110201:
                return "【用户未登录 QQ 或登录态失效】";
            case -1:
                return "【未获取到登录态】";
            default:
                if (code == 0) {
                    return "【QQ 未安装或 QQ 版本过低】";
                }
                return "【登录失败】";
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static JSONObject asJson(Object response) {
        if (response instanceof JSONObject) {
            return (JSONObject) response;
        }
        try {
            return new JSONObject(String.valueOf(response));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) {
                return v;
            }
        }
        return "";
    }
}
