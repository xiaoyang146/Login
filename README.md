# MixAuth 登录测试应用（纯 Java）

零第三方依赖的 QQ / 微信扫码登录测试程序，基于 GitHub 开源项目
[MixAuth](https://github.com/InvertGeek/mixauth) 的 HTTP 接口实现。

- 语言：纯 Java（JDK 17+），只使用 JDK 自带类库
  - `java.net.http.HttpClient`：发请求
  - 自带迷你 JSON 解析器 `Json.java`：不依赖 Gson/Jackson
  - `javax.swing`：桌面弹窗显示二维码（无图形环境自动降级为保存图片）
- 依赖：无任何 jar / Maven / Gradle 依赖

## 目录结构

```
mixauth-java-login/
├── src/com/example/mixauth/
│   ├── MixAuthLoginApp.java   # 入口：流程编排 + 状态机
│   ├── MixAuthClient.java     # MixAuth HTTP 客户端
│   ├── Json.java              # 纯 Java JSON 解析器
│   └── QrWindow.java          # Swing 二维码展示窗口
├── run.sh                     # 一键编译+运行
└── README.md
```

## 编译运行

```bash
# 编译
javac -encoding UTF-8 -d out $(find src -name '*.java')

# QQ 登录（桌面会弹出二维码窗口，用手机 QQ 扫码）
java -cp out com.example.mixauth.MixAuthLoginApp qq

# 微信登录
java -cp out com.example.mixauth.MixAuthLoginApp wechat

# 无图形环境 / 服务器测试：只保存二维码图片，终端提示路径
java -cp out com.example.mixauth.MixAuthLoginApp qq --no-gui
```

或者直接：

```bash
./run.sh qq
./run.sh wechat --no-gui --timeout=120
```

运行后会生成两个文件：

- `mixauth-qr-<qq|wechat>.png/.jpg` —— 二维码图片（QQ 为 PNG，微信通常为 JPEG）
- `login-result-<qq|wechat>.json` —— 登录成功后保存的完整响应

## 命令行参数

| 参数 | 说明 | 默认 |
|------|------|------|
| `qq` / `wechat` | 登录方式（位置参数） | `qq` |
| `--no-gui` | 不弹 Swing 窗口，只保存二维码图片 | 无图形环境自动启用 |
| `--timeout=秒` | 最长轮询时间 | `180` |
| `--interval=毫秒` | 轮询间隔 | qq=2000 / wechat=3000 |
| `--base-url=URL` | 指定 MixAuth 服务地址（逗号分隔多个） | 自动选择公开演示实例 |

## 实现原理（对应 MixAuth 接口）

| 步骤 | 接口 | 请求 | 响应关键字段 |
|------|------|------|--------------|
| 获取二维码 | `POST /api/qr` | `{"type":"qq"\|"wechat"}` | `data.id`、`data.qrcode` |
| 轮询状态 | `POST /api/status` | `{"type","id"}` | `data.*` + `signData` |
| 校验签名 | `POST /api/verify` | `{"sign"}` | （API 接入可不校验） |

状态解读：

- **QQ**：`data.code` + `data.message`。`code="0"` 且 `redirect` 非空即登录成功，
  同时返回 `qq`、`avatar`、`username`；`65` 或 message 含“已失效”表示二维码过期；
  其余 code（66 未失效 / 67 认证中）按官方中文 message 展示。
- **微信**：`data.wx_errcode` + `data.wx_code`。`404/405` 等待扫码、`402` 已扫码待确认、
  `408` 已过期、`201` 已确认（`wx_code` 出现）、`200` 用户取消；
  登录成功时 `data.success=true`，用户信息在 `data.data` 内（含 `UserIdStr` 等）。
  注意：微信通道只能拿到昵称/头像等基础信息，且返回字段随腾讯接口调整可能变化，
  建议用 `UserIdStr` 作为账号唯一标识。

> ⚠️ 说明：本程序默认连接 MixAuth 的公开演示实例，仅适合**测试/学习**。
> 正式使用请自行部署 mixauth（`src/config.js` 中更换秘钥），再用 `--base-url` 指向你的部署。

## 安全提示

- 任何第三方登录服务都应通过 `signData`/`/api/verify` 校验登录结果，避免伪造登录信息；
  本程序在成功输出时会打印 `signData`（格式：`时间戳|Base64(登录结果)|HMAC`），
  生产环境中请务必在你的后端调用 `/api/verify` 完成校验后再信任登录结果。
- 不要在生产环境依赖公开演示实例；它是共享 appid，随时可能调整或下线。