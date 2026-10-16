# C11 fix23 手动地图大小和位置

按 2026-10-16 确认的方案实现：接受重新连接生效，优先保持完整地图背景和固定渐隐。用户实测 fix22 已能移动地图；fix23 根据 `DiPlay-20261016-125337-016.txt` 扩大范围，并将位置读数改成明确的方向和像素。

在“设置 → 高级 → 仪表盘上的 CarPlay 地图（实验性）”内，用“地图大小、左右位置、上下位置”三个连续滑条替换 C11 地图的四档大小；“恢复地图大小和位置”恢复标准布局。保存后通过“重新连接”应用；修改本身不会停止当前 CarPlay。仅显示 iPhone 官方转向卡时保留原有卡片控制，其他车型的原有四档设置保持原行为。

## 行为与边界

- 地图大小 25%–200%，步长 1%。100% 对应原“标准”，150% 对应“较大”，175% 对应“最大”，50% 对应“最小”。这是供导航应用拟合全览的区域大小，百分比不是地图相机的缩放倍数，也不是单独的路名或车标字号调节功能。
- 左右和上下位置均可在画面 5%–95% 内移动，所有大小共用该范围；最大大小 200% 也可以移动。内部百分比和 fix22 使用同一坐标含义，避免覆盖安装后自定义位置跳变。
- 界面显示“向左/向右/向上/向下 N 像素”，以“恢复地图大小和位置”后的标准布局中心为零点。默认左渐隐 27%、顶渐隐 10% 时，参考中心为 63%/55%。像素是原生 720×720 流的布局中心偏移，不是投屏窗口的像素平移；例如实测的 47%/52% 显示为向左 115 像素、向上 22 像素。该读数不承诺导航应用把每个地物精确挪动同样像素。
- 滑条两端分别标明左/右、上/下，滑条向右拖动使地图布局中心向右/下，阿拉伯语也保持同一物理方向。值变化同步更新辅助功能状态，键盘提交后恢复焦点。
- 靠近画面边缘时，供 iPhone 拟合路线的矩形会自动缩小，让中心仍可继续移动。背景视频、窗口和渐隐不动，也不裁切视频。不能同时承诺将一个很大的全览矩形移到边缘并保持它的原有尺寸；导航应用可能缩小全览以容纳路线。
- fix22 在大小 150% 时受原矩形边界限制，水平仅能到 43%–57%、垂直仅能到 48%–52%。新范围两个方向均为 5%–95%，覆盖更大的调整空间。
- 保持原生 720×720 输出、固定投屏窗口、完整视频、透明渐隐及独立卡片。删除了 fix19 的右下偏移开关/纹理平移和 fix21 的蒙版平移，不会通过移动视频边缘制造左上空白。
- 修改配置的是发送给 iPhone 的地图安全区域。用户已确认 fix22 的地图可以移动；fix23 扩大后的边缘范围及高德/Apple Maps 如何拟合全览仍需实车验证，本地测试不能证明全部导航行为。已记录生效会话的大小、中心、输出尺寸和安全区域，方便发现“本地已发送但导航应用未响应”的情况。
- 诊断快照保留最后一次地图布局配置；在同一进程改成只显示 iPhone 官方转向卡后，可能仍看到之前的地图布局行，需结合会话日志时间和显示内容判断。

## 旧值和备份

在 `xcertplay_airplay` 中新增 `cluster_c11_map_size_percent`、`cluster_c11_map_center_x_percent`、`cluster_c11_map_center_y_percent`。这些键沿用现有 `cluster_` 备份白名单，不扩展到认证或配对数据。

没有新大小值时按旧 `cluster_map_scale_percent` 换算四档，保持等价的布局区域比例，并统一到 720×720 输出。首次位置默认值随当前渐隐宽度和大小计算。位置值只夹在统一的 5%–95% 范围；不再按所选大小夹到原拟合矩形的边界。重置明确写入标准大小并移除自定义位置，不再回退到旧档位。旧的像素偏移实验偏好即使还在，也不再读取。

关闭透明渐隐时保留同一套地图布局基准，避免切换渐隐就改变路线位置。若旧配置将某个渐隐宽度设为零，该方向使用 C11 测量默认值作为布局大小的计算基准；它不会重新打开视觉渐隐。大小与位置控制仍有效。

没有数据库迁移、权限、网络信任或签名策略改动。fix21 的 C11 音频固定播放速率策略保留。

## 源码与验证

主要文件：`LeapmotorClusterMapLayout.kt`（纯布局计算、保存和旧值换算）、`CarPlayHostActivity.kt`（实际连接配置）、`DiPlayActivity.kt`（三个滑条和重置）、`ClusterMapPresentation.kt`/`ClusterEdgeFade.kt`（撤销像素/蒙版移动）、`ClusterProjectionState.kt`（实际连接诊断）、8 个语言资源、mobile 版本号。

测试：`LeapmotorClusterMapLayoutTest` 验证旧值、大小/中心边界、备份恢复、真实 Presentation 纹理矩阵始终为单位矩阵；`LeapmotorClusterMapLayoutSettingsTest` 验证控件替换、保存/重置不会中断会话、重连提示、中阿文大字体的窄/宽布局和文字无垂直裁切；`CarPlayHostDisplaySizeTest` 验证实际 host 产生 720×720 和选定安全区，并记录诊断。既有渐隐像素、圆角和着色器缓存测试继续保留。

针对性命令实测通过（边界补充后的运行耗时 24 秒）：

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests com.shilapi.xcertplay.LeapmotorClusterMapLayoutTest --tests com.shilapi.xcertplay.LeapmotorClusterMapLayoutSettingsTest --tests com.shilapi.xcertplay.CarPlayHostDisplaySizeTest --tests com.shilapi.xcertplay.LeapmotorC11Test --tests com.shilapi.xcertplay.SettingsTranslationsTest --console=plain
```

fix22 已检查 `common/build/reports/c11-map-layout-ui/` 中中文大字体紧凑/宽屏和阿拉伯文紧凑截图。阿拉伯文重置按钮使用内容高度，文字换行不会被固定高度裁掉。`python scripts/check_public_tree.py` 和 `git diff --check` 已通过。尚未执行实车或设备 instrumentation。

## fix22 历史交付检查

在 `leap` 目录执行以下命令，最终运行 `BUILD SUCCESSFUL in 4m 59s`，完整日志在 `leap/build-fix22.log`：

```powershell
pwsh -NoProfile -File .\build-release-apk.ps1 -Name fix22
```

该脚本实际执行：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mobile:assembleRelease --console=plain
```

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared | 1438 | 0/0/0 |
| common | 1309 | 0/0/0 |
| home | 6 | 0/0/0 |

合计 2753 项。三个 Debug Lint、三个 Debug APK 与 mobile Release APK 均通过。保留既有 26 条非阻断 Lint 警告（mobile 18、home 7、maphost 1），包括未使用资源、调试字符串和依赖中的 TrustAllX509TrustManager 提醒；编译仍有既有弃用 API、Gradle 和 Java native-access 提醒。未通过修改无关校验来消除警告。边界复核期间的一次中途构建已主动停止，以上结果来自补充后最终完整运行；新布局回归替代已移除像素偏移实验的测试。

APK：`leap/dist/Diplay-0.2.20-C11-fix22.apk`，18426930 字节。包名 `com.shihab.diplay`、版本 `0.2.20-C11.22`、versionCode `44`、minSdk 25、targetSdk 37。v2 签名验证通过，证书 SHA-256 与 fix19/fix21 相同，两个本地认证资源与构建输入逐字节一致。分发文件与 Release 输出一致；fix21 原包哈希未改变。

APK SHA-256：

```text
56535AEF618E015B5811C94818689C179FB1D3932B90F3F3CCBC97FDF7E79FB8
```

独立只读复核没有发现阻断问题。最终源码工作树增量保存在 `leap/build-snapshots/fix22-source.zip`，以 `e1abc6aa05c60b6bbc29e6c6e599eccc1e70dfe7` 为基准，包含既有 fix20/fix21 内容及本次修改。

## 车测方式

覆盖安装，无需清数据。先用标准大小、默认位置重连；选择固定路线进入全览。随后每次只改一个位置方向并重连，观察整条路线的拟合位置；再试 120% 等中间大小。重点检查路线起终点、上下/左右位置是否响应，以及渐隐和背景是否保持原状。若布局未改变，导出报告检查 `clusterProjectionMapLayout`，不要把本地参数生效当成导航应用已响应。

## fix23 性能排查配套

按用户明确选择，新增“设置 → 高级 → C11 车辆日志读取（实验性）”，默认关闭，需要时手动开启并重连。停止的是 DiPlay 快速/慢速/事件普查的整条日志读取链；同时排除 C11 不适用的旧 BYD 电量预热和轮速读取，GPS 保留；保留地图、被动广播接收器和车机多媒体占用心跳。关闭会失去基于日志的倒车自动暂停/恢复、电量上报和速度读数。新增每 30 秒一条的进程 CPU/堆内存/系统空闲内存和主线程队列延迟摘要，帮助下一轮对照；此次没有修改视频离屏保留、解码恢复或请求帧率，以免混淆日志扫描开关的效果。详见 [fix22 性能排查](C11-FIX22-PERFORMANCE-20261016.md)。

fix23 针对性测试已通过（40 秒，包含布局、控件、日志读取门控、性能采样、实际 host 配置、语言和设置分类）。新增日志读取门控用例在修改前确实失败：`Default-off C11 must not create an ADB log reader`；地图范围用例在修改前失败：`expected 5..95 but was 37..63`。检查了新的中文和阿拉伯文大字体窄屏截图，方向标签与偏移值没有裁切。车辆旁路修正后的针对性测试也通过（36 秒，含 BYD 默认预热、明确不预热和恢复流程回归）；旧码下实际 host 配置用例失败 `C11 cannot read BYD wheel-speed services`。完整 CI 与发布包检查见下；当前无实车性能改善结论。

## fix23 完整交付检查

最终执行命令（在外层 `leap` 目录）：

```powershell
pwsh -NoProfile -File .\build-release-apk.ps1 -Name fix23
```

脚本执行的确切任务：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mobile:assembleRelease --console=plain
```

结果 `BUILD SUCCESSFUL in 6m 5s`，373 个任务（87 执行、286 up-to-date），完整日志 `leap/build-fix23.log`。补充 BYD 旁路门控前的一次打包已主动停止，保存在 `build-fix23-before-byd-gates-interrupted.log`，不作为最终验证依据。

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared | 1440 | 0/0/0 |
| common | 1322 | 0/0/0 |
| home | 6 | 0/0/0 |

合计 **2768** 项。三个 Debug Lint 均 0 错误；仍为既有 26 条非阻断警告（mobile 18、home 7、maphost 1），没有新增警告。三个 Debug 构建与 mobile Release 构建通过。保留既有弃用 API、Gradle/Java native-access 提醒，以及测试期间的 `incomplete input` 图像解码提示；未禁用校验或删测试。没有连接实车、模拟器或执行 instrumentation，卡顿改善仍待用户对照验证。

新增测试除先前布局外，包括 `RuntimePerformanceDiagnosticsTest`（单个待处理探针、30 秒采样、CPU 核当量、主线程延迟、关闭/入队竞态）、`C11VehicleLogSettingsTest`（默认无日志 ADB、可恢复快慢/普查、心跳/重连/备份、C11 排除旧 BYD 轮速且非 C11 保留）、`BydAppOpenBatteryTest`（禁用预热与默认兼容）。实际 C11 日志关闭时，设置 UI 不自动检查 ADB。中文紧凑/宽屏、阿拉伯文紧凑、大字体 1.5 倍的地图和新开关截图已生成并查看，保存于 `common/build/reports/c11-map-layout-ui/`。

主要增量文件：`LeapmotorClusterMapLayout.kt`、`DiPlayActivity.kt`、`AirPlayPersistence.kt`、`LeapmotorGearMonitor.kt`、`RuntimePerformanceDiagnostics.kt`、`CarPlayHostActivity.kt`、`backup/SettingsBackupCodec.kt`、`shared/.../BydNavigationOutputs.kt`、8 个语言资源、上述测试和 mobile 版本号。fix22 及更早的必要改动仍包含在当前工作树中。

APK：`leap/dist/Diplay-0.2.20-C11-fix23.apk`，18,452,758 字节，包名 `com.shihab.diplay`，版本 `0.2.20-C11.23`、versionCode 45、minSdk 25、targetSdk 37。v2 签名通过，证书 SHA-256 与旧包相同：`d1987cc9407fb9907a4f40ba77d9cf866d6d48eeca84213bcf7eecedc54df9d1`。本地连接认证资源存在并与构建输入逐字节一致；分发包与 Release 输出逐字节一致。fix22 原分发包 SHA-256 仍为 `56535AEF618E015B5811C94818689C179FB1D3932B90F3F3CCBC97FDF7E79FB8`。

fix23 APK SHA-256：

```text
5232BC08A337B7ABA0C7A2BEFE8448E3E84424C6EBA97C7B8A8A708A31BEBDC4
```

最终工作树源码增量保存为 `leap/build-snapshots/fix23-source.zip`，基于 `e1abc6aa05c60b6bbc29e6c6e599eccc1e70dfe7`，含 tracked.patch、基线和本次 APK 元数据、变更/新增工作文件。`git diff --check` 和 `python scripts/check_public_tree.py` 通过；没有数据库迁移、新权限或安全策略放宽。新日志偏好覆盖安装默认关闭，需恢复倒车日志暂停/电量上报时手动开启并重连；不移除旧 BYD 偏好，只在 C11 排除不适用的读取。
