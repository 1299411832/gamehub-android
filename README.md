# 游戏源神 Android 客户端

「游戏源神」（<https://mibear.top/>）的 Android 侧载壳：一个单 Activity 的 WebView 容器，
**不是**独立客户端。站点内容更新后 App 无需发版即可看到最新内容。

## 下载

固定下载地址（永远指向最新构建，由 CI 自动发布到 `latest` Release）：

```
https://github.com/xi7ang/gamehub-android/releases/latest/download/app-release.apk
```

国内推荐主地址（阿里云 ECS，由本仓库 GitHub Actions 构建后直接 rsync 上去）：

```
https://mibear.top/dl/gamehub-app.apk
```

侧载安装即可（首次安装需允许「安装未知来源应用」）。

## 壳的行为

- 只加载 `mibear.top` 及其子域；站外 http/https 链接交给系统浏览器。
- `quark://`、`uc://`、`xunlei://`、`baiduyun://`、`intent://` 等自定义 scheme 交给系统处理，
  未安装对应 App 时只弹 Toast，不崩溃。
- 返回键优先网页后退；顶部有细进度条；主框架加载失败显示离线/重试视图。
- 支持文件选择（Admin 页面上传）与 http/https 下载；下载到 `.apk` 完成后触发系统安装器。
- 屏幕旋转不重载页面。
- 权限仅 `INTERNET`、`REQUEST_INSTALL_PACKAGES` 与 `POST_NOTIFICATIONS`（资源更新提醒用，API 33+ 需用户授权），无任何分析/广告依赖。

## 资源更新通知

**没有推送服务**：不接 FCM（国内 ROM 普遍无 GMS）、不接厂商通道、不开长连接。
做法是 WorkManager 定期拉站点的 `/data/notify.json`，有更新才弹一条**每日摘要**本地通知。

- 数据源 `public/data/notify.json` 由站点仓库的 `scripts/gen-notify.js` 在构建期生成（只含最近 3 天的新增/更新），壳里不存任何推送服务端。
- 判据是 payload 的 `latestAt`，存进 `SharedPreferences` 做基线：首次运行只立基线不打扰；两次通知至少隔 20 小时；9 点前 / 22 点后不弹（推迟到白天那次检查）。
- **通知标题是随机的**：文案池在 `gen-notify.js` 的 `TITLES` 里，端上每次随机挑一条并填 `{count}` / `{top}` / `{cat}` 占位符。改钩子文案只需 push 站点仓库，不用发新版 APK。
- 点通知深链到 `/changelog.html`（`Notifications.EXTRA_TARGET_URL` → `MainActivity.onNewIntent`），站外地址会被 `SiteConfig.resolve` 拒掉。
- 代价：Doze 下检查会被推迟到几小时，不是秒级。对「资源更新」这种场景够用。

## 构建

构建全部在 GitHub Actions 上完成（`.github/workflows/build.yml`，push `main` 或手动触发），
本地产物不是发布路径。CI 使用 Gradle 8.9（仓库内**不提交** gradle wrapper 的二进制 jar）。

构建完成后 CI 做两件事：发布到 GitHub Release 的 `latest`，以及通过 rsync 把 APK 推到阿里云 ECS
的 `/home/www/gamehub-dl/gamehub-app.apk`（用 `gamehubdeploy` 用户，密钥走仓库 Secrets）。

```
gradle assembleRelease
```

## 签名

`app/build.gradle.kts` 从环境变量读取 release 签名信息；这些变量不存在时**自动回退到 debug 签名**，
构建不会失败。要启用正式签名，在仓库 Secrets 中配置以下变量即可（仅变量名，值请自行保管，
**不要**把 keystore 提交到仓库）：

| Secret 名称 | 含义 |
|---|---|
| `KEYSTORE_BASE64` | 签名 keystore（.jks）的 base64 编码内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

CI 会把 `KEYSTORE_BASE64` 解码为临时文件并导出 `KEYSTORE_FILE` 给 Gradle 使用。
未配置 `KEYSTORE_BASE64` 时构建产物为 debug 签名（CI 从第一天即绿，之后补上 secrets 无缝切换）。

## 什么时候需要重新发版

- **改站点内容（资源、文案、样式）→ 不需要发版。** App 只是加载线上站点，刷新即可见。
- **只有改壳逻辑（WebView 配置、scheme 处理、下载/安装、图标、权限等）才需要重新构建发版。**

## 工程规格

- Kotlin，XML 布局（不使用 Jetpack Compose）
- 包名 / applicationId：`space.devmini.gamehub`
- minSdk 26 / targetSdk 35 / compileSdk 35，versionCode 5 / versionName 1.0.4
- 矢量自适应图标（`mipmap-anydpi-v26` + 矢量前景，无二进制 PNG）
- `usesCleartextTraffic=false`
