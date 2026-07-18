# 全局实时同传 Android

这是独立于 Windows/Electron 版本的 Android 应用。它使用 Android 10+ 的 `AudioPlaybackCapture` 捕获手机中其他 App 允许共享的播放音频，再通过 Gemini Live Translate 输出实时字幕。

## 当前功能

- 捕获媒体、游戏等 App 的内部播放音频。
- 将 44.1/48 kHz 单声道或立体声 PCM 转换为 Gemini 所需的 16 kHz、单声道、PCM16LE。
- VAD 预录、静音切段、断线退避重连、会话恢复和 `goAway` 主动轮换。
- App 内字幕预览，以及只显示当前一句译文的悬浮字幕。
- 手机 Clash Meta VPN/TUN 透明代理。
- 可选手动 HTTP 或 SOCKS 代理，例如手机本地 `127.0.0.1:7890`。
- Gemini API Key 使用 Android Keystore 的 AES-GCM 密钥加密后存入应用私有设置。

## 系统要求

- Android 10（API 29）或更高版本。
- 目标 App 必须允许系统捕获其播放音频。
- Gemini Live Translate Preview 可用的 API Key。
- 如需代理，先在同一台手机中启动 Clash Meta 的 VPN/TUN。

部分 DRM 视频、通话、语音聊天和明确设置 `allowAudioPlaybackCapture=false` 的 App 无法捕获，这是 Android 平台限制，不能由本应用绕过。

## Clash Meta

推荐保持默认的 **系统网络（Clash VPN/TUN）**：只要手机上的 Clash Meta VPN 已连接，App 的 WebSocket 会自动经过它，无需填写端口。

只有在 Clash 没有开启 VPN/TUN、而是暴露本机监听端口时，才选择手动代理：

- HTTP：`127.0.0.1:7890`（以你的 Clash 配置为准）
- SOCKS：`127.0.0.1:<socks-port>`

“手机和电脑使用同一个机场/订阅”不代表两台设备的本地代理地址相同；这里的 `127.0.0.1` 始终指手机自己。

## 使用

1. 打开手机 Clash Meta 并连接 VPN/TUN。
2. 打开本 App，输入 Gemini API Key，目标语言保持 `zh-Hans`。
3. 允许录音、通知和悬浮窗权限。
4. 点击“开始捕获并翻译”，确认 Android 的录屏授权提示。
5. 切换到视频或播放器；允许被捕获的音频会显示为悬浮字幕。

本 App 不录制或保存视频画面。录屏授权仅用于 Android 的内部音频捕获 API。

## 下载与安装

从 [GitHub Releases](https://github.com/stwbeery/global-live-translator-android/releases) 下载最新 APK。首个版本标记为预发布，因为 Gemini Live Translate 本身仍处于 Preview 阶段。

- Android 可能提示允许安装未知来源应用，这是直接安装 GitHub APK 的正常流程。
- Release APK 使用固定项目签名，后续版本可以直接覆盖升级。
- 不要混装 Actions 中的 Debug APK 和 Release APK；二者签名不同，Android 不允许相互覆盖。
- 每个 Release 同时提供 `.sha256` 文件，可用于校验下载完整性。

## 构建

推荐 Android Studio Ladybug 或更高版本，JDK 17，Android SDK 35：

```powershell
cd android-app
gradle :app:testDebugUnitTest :app:assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

仓库中的 `.github/workflows/android-ci.yml` 会执行单元测试、Lint 和 Debug APK 构建，并上传 APK artifact。

`.github/workflows/release.yml` 可手动验证签名 Release 候选包；推送与 `versionName` 一致的 `v*` 标签时，会验证固定证书指纹并创建 GitHub prerelease。发布 keystore 和密码仅存放在 GitHub Actions Secrets 中，不进入仓库。

## 隐私和安全

- 不要把 API Key 写进代码、截图、日志或 Issue。
- API Key 只在手机 App 私有存储中以 Keystore 密钥加密保存。
- Gemini WebSocket 连接必须使用 TLS；网络错误会移除 URL 查询参数中的 Key。
- 音频会实时发送至 Gemini Live API，不在本地落盘。
- Release 构建不启用 HTTP Body/Header 日志。

## 致谢与许可

本项目沿用了桌面版的产品思路与 Gemini Live 会话经验。桌面版源自 NguyenKhanh 的 MIT 项目 [live-translate-companion](https://github.com/xShiroeNguyenx/live-translate-companion)，详细说明见 [NOTICE](NOTICE)。Android 实现采用独立 Kotlin 代码，按 [MIT License](LICENSE) 发布。
