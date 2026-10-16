# C11 fix24：保守精简与验证记录

用户约束：这是独立的功能精简任务，不预设为车机卡顿原因；仅清理确认与 C11 无关的功能，共享或用途有疑问的实现保留。

实施计划（以 fix23 工作树为基线）：

1. `shared` 统一已有 C11 指纹判定；BYD 专属有效开关与读取/输出入口拒绝在 C11 启动。保留原偏好、通用 iAP2 路线/通话状态、电话音频、ADB、无线/蓝牙及 OEM 历史状态恢复。
2. `common` 清理 C11 的高级 BYD 数据、DiLink 向导和仪表专属控件、搜索与侧栏数据入口；保留 C11 地图布局/渐隐、音乐和转向卡、Siri/通用外接控制器以及可选 C11 日志读取。
3. 物理删除依赖闭合且已复核为 BYD 专用的 SOME/IP HUD、灯光音乐联动、HUD 测试桥及资源。其余与共享状态、布局或恢复交织的实现先保留，只在 C11 禁止执行。不批量重命名，不清应用数据，不调整音频时钟或视频策略。
4. 增加旧 BYD 偏好开启时 C11 不启动读取/探测/输出的回归检查，以及导航/通话共享状态与通用设置仍可用的检查。先跑最小相关测试，再执行仓库 CI、发布构建、签名/资产核验及设置截图检查。

主要涉及文件：`shared/.../HeadUnitPlatform.kt`、`shared/.../hud/BydOutputSettings.kt`、`BydNavigationOutputs.kt` 及专属读取入口；`common/.../LeapmotorClusterLayout.kt`、`DiPlayActivity.kt`、`CarPlayHostActivity.kt`、`AirPlayPersistence.kt`、setup；纯 HUD/灯光实现、媒体灯光钩子与专用资源/测试。

基线：保留 fix23 源码快照和 APK，按该快照逐项复核增量，而不是按 `Byd` 文件名批量删除。任务/模块发现已通过（工作区根目录 `build-fix24-task-discovery.log`）。本轮最终发布为 `0.2.20-C11.24`。

## 实际删除

| 范围 | 删除内容与边界 |
| --- | --- |
| BYD SOME/IP HUD | `BydHudBridge/Protocol/Payload`、`BydStarterBridge`、SOME/IP 包查询、50 个 HUD 图标和配套声明；保留 iPhone 路线解析、机动类型映射和本地转向卡。此 C11 分支不再提供这条 BYD HUD 发送能力。 |
| BYD 灯光和音乐联动 | 灯光 client/policy/worker、所有权锁、灯光会话和设置、封面取色、音乐包络/低频分析及定时控制；从媒体链路移除仅服务灯光的钩子。保留正常解码/播放、封面加载/缓存、歌曲状态、MediaSession 和仪表音乐卡。 |
| 专用 UI 与调试入口 | 8 个语言目录中的 35 组灯光文案、灯光编辑入口，HUD 专用调试 Activity/Receiver 及相应权限/查询声明；保留 debug 包标识。通用环境光传感器、自动昼夜模式和延迟设置保留。 |
| 专用验证代码 | 删除上述已删除功能的 18 个专用测试文件，新增 C11 隔离和保留功能的回归测试；音频测试仍保留原有断言，只适配移除灯光参数后的构造器。 |

共删除 19 个主源码 Kotlin 文件、2 个 debug Kotlin 文件及 50 个 HUD 资源文件。HUD 资源原始合计 84,870 字节，最终 APK 比 fix23 实际减少 182,253 字节。包体变化不代表已测得运行性能提升。

## 保留实现，但在 C11 隐藏并禁止启动

以下代码与共享数据、投影布局或旧状态恢复仍有耦合。本轮保留实现，统一沿用既有 `qti/msmnile_gvmq` C11 指纹规则，在设置和实际执行入口同时限制：

- BYD autoservice 电量、续航、轮速/挡位、胎压、车辆字段探测；旧偏好即使为开启，C11 也不启动对应读取或探测，侧栏不订阅这组数据。
- BYD/DiLink 原厂仪表输出、歌曲/通话硬件写入及 watcher、仪表速度计、BYD 小窗、导航模式跟随和暂停地图流、摇杆/原厂地图缩放。
- BYD 来电弹窗处理在 C11 不创建新的所有者、工作任务或 ADB 会话；额外覆盖 Android 12L，避免仅依靠当前车机的 Android 9 限制。
- C11 初次使用向导不再选择 DiLink 版本，也不套用其音频或车辆功能默认值；BYD 数据卡和专属搜索项不显示。

原始偏好和备份格式保留，覆盖安装无需清数据。此前的 BYD 开关不会重新激活 C11 上已禁用的硬件路径。已有 OEM/DiLink 恢复日志仍可触发恢复，不能为精简而丢失未完成的恢复记录。

## 明确保留

- C11 仪表完整 720×720 背景、固定渐隐、源端地图位置/大小、保存后重连、转向卡和音乐卡；本轮不修改 fix23 地图布局算法。
- iPhone iAP2 导航/通话状态、CarPlay 连接、媒体信息和封面、播放/录音/回声消除。`BydNavigationOutputs`、`BydHudRouteState` 和 `BydCarPlayCall` 含有共享职责，不能整类删除。
- C11 广播方向盘键；可选的 C11 日志读取仍默认关闭，需要时可开启。不开启时保持 fix23 的行为。
- 通用 Siri、外接键盘/控制器、导航音量键、车机电话音频路由、蓝牙暂停与恢复、Wi-Fi 兼容、本地 ADB、启动修复和系统状态恢复。
- FCE 是独立适配，保留实际服务检测和单独开关；服务不存在时不为清理闲置输出创建线程。服务存在时，连接后再打开开关仍可接收导航，并在断开时清除输出。C11 不要求先开启已隐藏的 BYD 总开关。
- 其他车型或用途仍不确定的布局、侧栏、桌面、分屏与恢复实现不做进一步物理删除，不批量改名。

## 验证结果

2026-10-16，本地 Windows / Android SDK / Gradle 环境：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest `
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug `
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mobile:assembleRelease
python -X utf8 scripts/check_public_tree.py
git diff --check
```

- 先完成模块/任务发现和相关最小测试，再执行上述仓库 CI 与发布构建。最终 CI/发布命令通过；发布辅助脚本在私有构建输入环境中调用相同 Gradle 任务，日志为工作区根目录 `build-fix24.log`。
- 单元测试：shared 1,367、common 1,307、home 6，共 **2,680** 项，**0 失败、0 错误、0 跳过**。home/maphost 实际目录为 `samples/home`、`samples/maphost`。
- Lint：**0 错误、12 条警告**（mobile 4、home 7、maphost 1）。保留的警告包括现有 TLS 信任管理器、资源/API 兼容和样例布局/构建建议；没有关闭检查。编译仍有原有弃用 API、类型推断和未来 Gradle 兼容提示，本轮不扩展修改范围。
- 新回归覆盖旧 BYD 偏好开启、读取/探测入口、C11 无新增厂商任务、真实 iPhone 路线/通话帧、重连保持转向卡、控制器构造期间不触发回调，以及 FCE 在连接中切换并断开清理。
- 设置/向导使用 Robolectric native graphics 检查中文紧凑/完整宽度和阿拉伯文 RTL，字体倍率 1.5；6 张截图已人工查看，输出位于 `common/build/reports/c11-cleanup-ui/`。这不是模拟器或车机截图。
- 初次全量验证暴露音频反射测试仍按已删除的灯光构造器参数定位，以及新测试假设单例从未被前面的测试启动。已修正测试夹具：原音频断言保留，隔离测试检查调用前后新增任务/读取和状态变化，不删除、跳过或放宽生产行为要求。修正后共享全量及完整 CI 均通过。
- 发布包核对 DEX 类定义：19 个被删除的主源码类族和 2 个 debug 类族均不在包内；抽查 13 个共享/C11 核心类保留；HUD 图标不存在。APK ZIP CRC 通过。
- 发布包认证资产与私有构建输入及 fix23 字节一致；签名验证通过，与 fix23 为同一证书。fix23 APK 哈希未变化。
- 公共源码扫描和差异空白检查通过。源码快照另存为工作区根目录 `build-snapshots/fix24-source.zip`，包含基准信息、当前改动文件、删除清单及二进制补丁，不包含认证资产或签名私钥。

发布候选包：`dist/Diplay-0.2.20-C11-fix24.apk`（位于外层工作区）。包名 `com.shihab.diplay`，versionCode `46`，versionName `0.2.20-C11.24`，minSdk 25 / targetSdk 37；大小 18,270,509 字节，SHA-256 `219BAF4BB11C9DAF09C4DA2EFD83351D41EB9D65FEEEA411B0FA270EBB5DE277`。APK v2 签名通过，签名证书 SHA-256 为 `d1987cc9407fb9907a4f40ba77d9cf866d6d48eeca84213bcf7eecedc54df9d1`，与 fix23 相同。ZIP CRC、4 字节对齐和两个本地认证资源检查通过。

初始 fix24 APK 使用 versionCode 45，与 fix23 相同，不能保证可覆盖升级；该包已被本候选替代。versionCode 46 修正了升级顺序，不代表完成 C11 实车验收。

- 大小：18,270,509 字节。
- SHA-256：`219BAF4BB11C9DAF09C4DA2EFD83351D41EB9D65FEEEA411B0FA270EBB5DE277`。
- 签名证书 SHA-256：`d1987cc9407fb9907a4f40ba77d9cf866d6d48eeca84213bcf7eecedc54df9d1`。

尚未执行车机安装、模拟器/仪器测试或整机性能对比。仍需在 C11 验证连接、地图/渐隐、转向/音乐卡、方向盘键和听感；本轮精简不作为卡顿根因结论。
