# DiPlay 零跑 C11 适配版

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**本仓库是专为零跑 C11 Android 车机适配的 DiPlay 分支。** 它不是面向比亚迪车机的上游版本。车机系统指纹包含 qti/msmnile_gvmq 时会启用 C11 专属逻辑；其他 C11 固件和其他车型尚未确认兼容。

当前版本：0.2.20-C11.24（versionCode 46）。应用包名：com.shihab.diplay；最低 Android 版本：7.1（API 25）；target SDK：37。

## C11 适配内容

- 通过 USB 或无线连接 CarPlay；无线方式取决于车机固件，可用车载热点、Wi-Fi Direct 或现有 Wi-Fi 网络。
- 在 C11 仪表显示 CarPlay 导航画面，可调整地图大小、位置和边缘渐隐；保留音乐卡片和转向提示卡片。保存地图布局后重新连接生效。
- 接收 C11 方向盘媒体按键广播，处理播放、暂停和切歌。
- 可选读取 C11 车辆日志，**默认关闭**。启用后通过已授权的网络 ADB 读取挡位、电量、车速和方向盘事件；日志也可支持倒车时暂停、离开倒车后恢复音乐。核心 CarPlay 连接不需要 ADB。

BYD 专用 SOME/IP HUD、氛围灯／音乐分析及 BYD 车辆数据路径已在 C11 版本移除或禁用；旧 BYD 设置不会在 C11 上重新启用它们。

## 安装与更新

请把 C11 APK 安装在零跑 C11 车机上，不要安装到 iPhone。从[本仓库的 Releases](https://github.com/w012w012/DiPlay-Leapmotor/releases)取得 C11 构建；若没有合适发行包，可按[构建指南](docs/BUILD.md)自行构建。不要用比亚迪上游 APK 替换此分支。

本分支和上游使用相同应用包名，但签名不同，不能并排安装，也不能直接互相覆盖更新。卸载会删除应用私有设置和配对数据。

应用内背景更新检查目前查询上游 DiPlay 的 GitHub Releases，而非本 C11 分支，可能提示不适用的上游 APK。请在 **设置 → 关于 → 背景更新检查** 关闭该功能，并从本仓库取得 C11 更新。

## 已知限制

这是社区维护的适配版，不是 Apple 或零跑认证产品。C11 功能依赖车机系统指纹，未确认所有 C11 年款和固件均兼容。fix24 的自动化检查记录在[清理与验证记录](docs/C11-FIX24-CLEANUP.md)，但当前版本**尚未完成实车验收**；连接、仪表地图与渐隐、音频、方向盘操作和长时间使用仍需在目标 C11 上确认。

发布 APK 使用实验性配件身份，其私钥可以从 APK 提取。未来 iOS 兼容性无法保证；此 APK 不是 Apple 认证产品，也不应作为安全凭据。

## 文档

- [简体中文安装与更新说明](README.zh-CN.md#安装与更新) · [繁体中文安装指南](docs/INSTALL.zh-TW.md) · [英文安装指南](docs/INSTALL.md)
- [C11 兼容性与限制](docs/COMPATIBILITY.md)
- [C11 地图大小和位置](docs/C11-MANUAL-MAP-LAYOUT.md)
- [fix24 清理范围与验证](docs/C11-FIX24-CLEANUP.md)
- [隐私与诊断](docs/PRIVACY.md)
- [构建指南](docs/BUILD.md)
- [当前版本说明](docs/RELEASE-NOTES-0.2.20-C11.24.md) · [项目变更记录](CHANGELOG.md)
- [第三方声明与许可证](docs/THIRD_PARTY_NOTICES.md)

## 问题反馈

请提供 C11 年款／车机固件、Android 版本、iPhone／iOS 版本、连接方式、相关设置、复现步骤和故障时间。可从 **设置 → 诊断 → 保存诊断报告** 导出日志。公开分享前请检查并删除个人信息，不要附热点密码或认证文件。[在本仓库提交问题](https://github.com/w012w012/DiPlay-Leapmotor/issues)。

## 来源与授权

接收端基于 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0），设置界面与网站改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）。分发修改版本时请保留相关许可证和[第三方声明](docs/THIRD_PARTY_NOTICES.md)。DiPlay 与 Apple、零跑及比亚迪均无隶属关系；CarPlay 及其图标属于 Apple Inc。
