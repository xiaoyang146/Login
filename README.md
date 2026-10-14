# 登录测试合集（纯 Java）：手机 QQ 一键登录 + MixAuth 扫码登录

一个仓库，三条登录通道，全部用 **纯 Java** 写（不写 Kotlin）：

| 通道 | 实现方式 | 适用形态 |
|---|---|---|
| **手机 QQ 一键登录** | 腾讯官方 QQ 互联 Android SDK 3.5.19（SSO，直接拉起手机 QQ 免密授权） | Android App |
| 微信 / QQ 扫码登录 | MixAuth HTTP 接口，App 内展示二维码轮询 | Android App |
| 扫码登录（桌面） | 同上，Swing 弹窗显示二维码 | 桌面 JAR（JDK 17+） |

---

## 一、先说清楚：为什么"纯 Java"只能这么写

**纯 Java SE（JDK / Spring / Swing）无法"调起手机 QQ 登录"。** 这件事依赖两个 Android 独有机制：

1. QQ 客户端（`com.tencent.mobileqq`）对外暴露的授权 Activity，只能通过 Android 的 `Intent` 跨应用拉起；
2. QQ 授权完成后，通过 `tencent{你的AppID}://` 这个自定义 scheme 回调你的 App，靠的是 `IntentFilter` 分发。

所以"纯 Java"的可行含义是：**原生 Android 工程，业务代码全部用 Java**。本仓库就是这么做的。

> 补充：官方 SDK 的 jar 是纯 Java 的，腾讯把少量 `.so` 放在了 SDK 压缩包的 demo APK 里，
> 本工程不需要那些 `.so`，因此依赖仍然是"一个 jar"。

---

## 二、目录结构

```
Login/
├── src/com/example/mixauth/            # 桌面版（纯 JDK，无任何第三方依赖）
│   ├── MixAuthLoginApp.java            #   入口：流程编排 + 状态机
│   ├── MixAuthClient.java              #   MixAuth HTTP 客户端
│   ├── Json.java                       #   自带迷你 JSON 解析器
│   └── QrWindow.java                   #   Swing 二维码窗口
├── android/                            # Android 版
│   ├── build-apk.bat                   #   Windows 一键构建
│   ├── qileys.keystore                 #   ⚠️ 固定签名证书（QQ 登录必须签名稳定）
│   ├── app/libs/open_sdk_3.5.19_*.jar  #   腾讯 QQ 互联官方 SDK（随仓库内置）
│   └── app/src/main/
│       ├── AndroidManifest.xml         #   AuthActivity / AssistActivity / FileProvider / queries
│       ├── java/com/example/mixauthlogin/
│       │   ├── MainActivity.java       #   主界面：隐私合规 + 三条通道 + 登录态展示
│       │   ├── QqSdkAuth.java          #   ★ 官方 SDK 封装（SSO 拉起手机 QQ）
│       │   ├── MixAuthClient.java      #   MixAuth HTTP 客户端
│       │   ├── QqLogin.java            #   QQ互联 Web 流程（code→token→openid→用户信息）
│       │   └── QqOAuthActivity.java    #   旧 WebView 授权页（保留但已从主入口移除，见下）
│       └── res/{layout,values,xml}     #   界面、文案、FileProvider 路径
├── scripts/fetch-qopensdk.sh           # 重新下载 / 升级官方 SDK jar
└── .github/workflows/                  # CI：自动出 APK + 桌面 JAR
```

### 关于旧的 WebView 授权页（重要）

`QqOAuthActivity` 用 WebView 打开 `https://graph.qq.com/oauth2.0/authorize`，而 `redirect_uri` 填的是
`tencent102064194://auth.qq.com/`。**这个组合是不可能成功的**：WebView 打开的是 `https://` 页面，
永远不会跳转到 `tencent...://` 这个自定义 scheme，所以回调 URL 拦不到，拿不到 `code`。

`tencent{AppID}://` 这类 scheme 的唯一用途，是**给官方 SDK 的 SSO 流程回跳用**。
如果确实想走 WebView 授权，必须在 QQ互联后台登记一个**真实的 https 回调地址**（如 `https://你的域名/qq/callback`），
在 WebView 里拦截这个地址拿 `code`。

本仓库已经把主入口切到官方 SDK，`QqOAuthActivity` 仅作为参考保留。

---

## 三、构建

### 方式 1：GitHub Actions（推荐，零环境依赖）

推送到 `main` 后自动触发，或到 **Actions → Build Android APK → Run workflow** 手动触发。
构建完成后在 **Artifacts** 下载 `qq-login-apk`（包含 debug 与 release 两个 APK）。
日志里会打印 **APK 签名证书的 MD5**，用于填 QQ互联后台。

### 方式 2：Windows 本地

前置：JDK 17+、Android SDK（`platforms;android-34` + `build-tools;34.0.0`）。

```bat
cd android
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
build-apk.bat
```

> 仓库里只有 Unix 的 `gradlew`，没有 `gradlew.bat`，所以 `build-apk.bat` 直接用
> `java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain` 调 Gradle。

### 方式 3：Linux / macOS

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:assembleRelease
```

### 桌面版 JAR

```bash
./build.sh          # 产出 mixauth-login.jar
./run.sh qq         # 桌面扫码登录（javax.swing 弹窗）
./run.sh wechat --no-gui --timeout=120
```

---

## 四、⭐ 上线前必做：QQ互联后台三项配置

手机 QQ 登录失败 90% 都出在这里。腾讯会**同时校验包名和签名**，任何一项对不上就返回 `100044`。

| 配置项 | 必须填成 | 从哪来 |
|---|---|---|
| 应用包名 | `com.android.qileys` | `android/app/build.gradle` 的 `applicationId` |
| 应用签名 MD5 | `qileys.keystore` 的 MD5 | 见下方命令 |
| `android:scheme` | `tencent102064194` | `AndroidManifest.xml`，必须等于 `"tencent" + QQ_APP_ID` |

取签名 MD5（32 位、小写、去掉冒号）：

```bash
# 从已构建的 APK 取（最准确，CI 日志里也会打印）
keytool -printcert -jarfile android/app/build/outputs/apk/debug/app-debug.apk

# 或直接从 keystore 取
keytool -list -v -keystore android/qileys.keystore -alias qileys -storepass android123
```

把输出里的 `MD5: XX:XX:...` 去掉冒号、转小写，填进 connect.qq.com 该应用的「应用签名」。

**换 AppID 要改三处**（漏一处就失败）：

1. `android/app/build.gradle` → `QQ_APP_ID`；
2. `AndroidManifest.xml` → `AuthActivity` 的 `<data android:scheme="tencent102064194" />`；
3. `android/app/build.gradle` → `QQ_REDIRECT_URI`（如果还走 Web 流程）。

---

## 五、Android 端关键实现点

```java
// 1) 隐私合规：用户同意之后才能调 SDK（3.5.7+ 强制，否则所有接口直接失败）
Tencent.setIsPermissionGranted(true);

// 2) 创建实例（第三个参数 = Manifest 里 FileProvider 的 authorities）
Tencent tencent = Tencent.createInstance("102064194", appContext,
        com.tencent.connect.common.Constants.FILE_PROVIDER_AUTHORITIES);

// 3) 发起登录（listener 必须是成员变量！SDK 内部是弱引用）
tencent.login(activity, "get_user_info", loginListener);

// 4) 结果回传（3.5.19 的 Tencent 没有实例方法 onActivityResult，用官方静态入口）
@Override
protected void onActivityResult(int req, int res, Intent data) {
    Tencent.onActivityResultData(req, res, data, loginListener);
    super.onActivityResult(req, res, data);
}
```

`QqSdkAuth.java` 已把这些都封好，并且：

- 用 `SharedPreferences` 持久化 `openid / access_token / 有效期 / 昵称 / 头像`，支持静默校验登录态；
- 把常见错误码翻译成中文提示（`100044` 直接告诉你"签名对不上"）；
- 提供 `isQQInstalled()` 与 `resetTargetAppInfoCache()`（用户本次才装好 QQ 时，需要重置 SDK 的安装状态缓存，否则一直走 H5 降级）。

---

## 六、常见问题排查

| 现象 / 错误码 | 原因与处理 |
|---|---|
| `100044` 签名校验失败 | 后台登记的包名或签名 MD5 与实际 APK 不一致。用上一节的 `keytool` 取 MD5 重填；注意别用随机 debug key（本工程已强制固定 keystore） |
| 点登录毫无反应 | `AuthActivity` 未声明；或 `<data android:scheme>` 的 AppID 写错；或 `setIsPermissionGranted(true)` 没调 |
| 从 QQ 返回后没有回调 | `onActivityResult` 里没调 `Tencent.onActivityResultData(...)`；或 listener 被 GC（必须是成员变量） |
| 装了 QQ 却仍走网页授权 | Android 11+ 缺 `<queries>`；或 SDK 缓存了"未安装"，调 `Tencent.resetTargetAppInfoCache()` |
| `NoClassDefFoundError: android.support.v4.content.FileProvider` | SDK 内部仍用旧 support 库的 FileProvider，需保留 `com.android.support:support-v4:28.0.0` 依赖 + Manifest 里的 provider 声明 |
| 构建报 `Could not find com.android.support:support-v4` | 需要 `maven.google.com` 仓库（本工程 `dependencyResolutionManagement` 已含 `google()`） |
| 混淆后崩溃 | 加 keep 规则：`-keep class com.tencent.** { *; }` |
| 上架审核被拒 | 隐私政策里必须声明引用了「QQ互联SDK」并说明采集范围 |

---

## 七、安全提示

1. **`QQ_APP_KEY` 目前写在 `buildConfigField` 里，等于随 APK 公开发布。**
   官方 SDK 的 SSO 登录**不需要** AppKey，它只在「code 换 token」的服务端流程里用。
   正式产品请把 AppKey 移到自己的后端，客户端只上报 `access_token` / `openid`，
   由后端调 `https://graph.qq.com/oauth2.0/me` 反查校验后再签发自己的登录态。
2. `qileys.keystore` 和密码（`android123`）都在仓库里，仅供本 Demo 保证签名稳定。
   **正式发版请换成自己的 keystore，并把密码放到 CI Secrets**，同时重新登记签名 MD5。
3. MixAuth 通道默认连公开演示实例（共享 appid），只适合测试/学习；
   生产请自行部署 mixauth 并用 `--base-url` 指向自己的部署，并在后端调 `/api/verify` 校验 `signData`。

---

## 八、参考

- [QQ互联 · Android SDK 环境搭建](https://wiki.connect.qq.com/qq%E7%99%BB%E5%BD%95)
- [QQ互联 · SDK 下载（Android 3.5.19）](https://wiki.connect.qq.com/sdk%E4%B8%8B%E8%BD%BD)
- [QQ互联 · 使用 Authorization_Code 获取 Access_Token](https://wiki.connect.qq.com/%E4%BD%BF%E7%94%A8authorization_code%E8%8E%B7%E5%8F%96access_token)
- [QQ互联 SDK 隐私保护声明](https://wiki.connect.qq.com/qq%e4%ba%92%e8%81%94sdk%e9%9a%90%e7%a7%81%e4%bf%9d%e6%8a%a4%e5%a3%b0%e6%98%8e)
- [MixAuth](https://github.com/InvertGeek/mixauth)
