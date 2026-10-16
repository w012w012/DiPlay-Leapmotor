# C11 fix19 车测问题排查与 fix21

后续说明：fix21 的画面方案未达到“保持视野面积、让导航全览居中”的目标，已在 fix22 撤回；本文件保留当时的排查证据和构建记录。当前方案见 [手动地图布局](C11-MANUAL-MAP-LAYOUT.md)。fix21 的 C11 固定播放速率修复继续保留。

日期：2026-10-16。输入：`leap/log/DiPlay-20261016-081239-806.txt`，应用版本 `0.2.20-C11.19`，Android 9/API 28，C11 高通车机，无线连接。当前工作树还包含已交付 fix20 的颜色渐变清理；本次在该基础上修正两个问题，保留 fix19/fix20 APK 与源码快照。

## 画面右下偏移后过渡消失

日志第 1964、1972–1974 行记录同一会话内开关在 `right=108 down=72` 和 `right=0 down=0` 间切换。透明渐隐父容器仍在，报告为 `left=27% top=10% corner=36px above=TextureView`。报告头的 `enabled=false` 是导出时的最后状态，不代表整个测试都未开启。

fix19 仅用 TextureView 的矩阵平移视频，没有平移透明蒙版坐标。在 720×720 下，顶部渐隐原来覆盖 y=0..72；视频的新顶部变为 y=72，整个顶部渐隐落在透明空白里，新边缘直接全不透明。左侧渐隐原来覆盖 x=0..194.4；新左边缘 x=108 已处在渐隐中段，透明度从约 56% 突然开始，剩余过渡宽度只有约 86 像素。两条渐隐及圆角均与实际视频边缘错位。

新增 `LeapmotorClusterMapOffsetTest.translatedVideoStillFadesAtBothVisibleEdgesAndRestoresLive`：通过真实 Presentation 的纹理矩阵绘制白色视频帧替身，并经过实际 `ClusterEdgeFadeFrame` 的原生 Canvas 蒙版。修复前实测失败：`visible top edge must start transparent: 255`。此测试验证边缘 alpha、两条过渡完整宽度、圆角位置、内部不透明、开关恢复及 480×480 重布局。替身用于模拟解码后的不透明视频内容，未运行真实车机 TextureView/GPU。

fix21 保持窗口、纹理 View、比例、协议安全区与独立卡片位置不变，让渐隐绘制坐标跟随视频平移。不会把整个窗口移出原仪表区域。仅在偏移变化时 invalidate，着色器仍按尺寸缓存；日志改为 `fade=content-edge`。

## 音乐音调浮动

日志不是单纯显示缓冲波动，还记录了程序主动设置的 `playbackRate`。同一 48 kHz AAC 音乐流的例子：

| 日志时间 | 播放速率 Hz | 相对 48000 |
| --- | ---: | ---: |
| 08:12:18.677 | 47611 | -0.81% |
| 08:12:23.712 | 48709 | +1.48% |
| 08:12:28.720 | 47668 | -0.69% |
| 08:12:33.721 | 48683 | +1.42% |

这些行的 `dropped=0`、`underruns=+0`、`writeErrors=0`，接收统计同时显示约 389–410 ms 的突发间隔。`sampleRate=48000` 是流/AudioTrack 格式，不能据此认为播放速度未变；`AudioRenderer.trimClock()` 将 `MediaClockTrim` 输出传给 `AudioTrack.setPlaybackRate()`，会同时改变采样播放速度和音调。控制范围是 ±1.5%，约 ±26 音分。

全文件共 339 条音乐速率统计，最小 47280、最大 48709 Hz；轨迹是反复升降，不能从日志证明“持续单调走低”。控制器每秒调整，平均窗口标称 5 秒，但采样间隔超过 250 ms 会重置到瞬时水位误差。因此无线突发/阻塞写入可能放大变速，此点是代码推断；临时零硬件漂移的突发 harness 未获得有效验证结果，不能宣称已证明它是唯一触发条件。已有单测实测确认低水位会将 48000 调到 47280 Hz，仍不等于重放用户歌曲或音质。

关联上游代码的证据很强，但不能将起因全部归到最后一次合并：

- `904d78b0` 已在 fix17 阶段移植 `dfd7c3d6` 的变速时钟补偿。
- `e1abc6aa`（fix18）继续移植压缩包积压计入水位、重缓冲诊断、陈旧包保护和麦克风恢复。fix19 保留这些音频实现；偏移功能不改音频。
- 本次日志能证明 fix19 正在主动改变播放速率，机制与听感相符；没有 fix17/fix18 的同曲录音及同步日志，不能断言听感首次出现在哪个版本，也不能以本次日志排除声源或车机 DSP 的额外影响。

fix21 对已有 C11 固件识别 `qti/msmnile_gvmq` 关闭这项变速控制，由 host 通过默认开启的 `mediaClockTrimEnabled` 参数传入共享 sink 和 renderer。音乐按协商的原采样率播放；其他设备继续使用既有时钟补偿。缓冲大小、重缓冲、短时突发保留、陈旧包恢复、音频线程优先级、麦克风和通话路径保留。

诊断新增 `Audio: ready ... clockTrim=off`，关闭补偿后的统计从实际 AudioTrack 读取 `playbackRate`。48 kHz 曲目应为 `48000`。没有增加设置项、权限、网络信任或存储迁移。

代价：如果 C11 真有持续硬件时钟偏差，固定速率可能在长期播放后暴露缓冲不足或积压，需观察 `underruns`、`rebuffers` 和队列；不能用本地测试替代长期车测。

## 回归验证与车测

实际通过：Gradle 模块/任务发现；画面修复后 `LeapmotorClusterMapOffsetTest` 与 `LeapmotorC11Test`；针对音频、重缓冲、解码输出释放及实际 host 配置的检查。新增 host 回归在修复前失败：`C11 must not change music pitch with buffer level expected null, but was MediaClockTrim`，修复后通过。共享 sink 回归验证关闭后低/高水位均不调用 `setPlaybackRate`、不继承之前学到的变速值、PCM 数据不被修改；其他设备的控制器及重缓冲测试继续通过。

完整交付命令（在 `leap` 目录）实测通过，耗时 6 分 15 秒，日志保存在 `leap/build-fix21.log`：

```powershell
pwsh -NoProfile -File .\build-release-apk.ps1 -Name fix21
```

脚本实际执行以下 Gradle 任务，随后核对认证资源和签名：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mobile:assembleRelease --console=plain
```

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared | 1438 | 0/0/0 |
| common | 1306 | 0/0/0 |
| home | 6 | 0/0/0 |

合计 2750 项。三个 Debug Lint、三个 Debug APK 与 mobile Release APK 均通过。Lint 保留既有 26 条非阻断警告（mobile 18、home 7、maphost 1），包括未使用资源、调试字符串和依赖中的 TrustAllX509TrustManager 提醒；编译仍有既有弃用 API、Gradle 与 Java native-access 提醒。图片错误输入测试打印的 `incomplete input` 提示在 fix20 构建日志中同样存在，测试结果无失败。未为通过检查而调整无关代码或校验。

`python scripts/check_public_tree.py`、`git diff --check` 均通过。独立只读复核未发现阻断问题；最终以主代理实测的完整检查结果为准。

分发包：`leap/dist/Diplay-0.2.20-C11-fix21.apk`，大小 18425142 字节；包名 `com.shihab.diplay`，versionName `0.2.20-C11.21`、versionCode `43`，minSdk 25、targetSdk 37。`apksigner verify` 通过 v2 签名；证书 SHA-256 与 fix19 一致。包内两个本地认证资源与构建输入逐字节一致。可覆盖安装，无数据迁移。fix19/fix20 原包 SHA-256 仍与各自交付记录一致。

fix21 APK SHA-256：

```text
3FB52BC37FC3D9B44D673A6C0154C1A6722F42802D53B23F1636C33B5D677796
```

改动文件：`ClusterEdgeFade.kt`、`LeapmotorClusterMapOffset.kt`（蒙版坐标与诊断）、`CarPlayHostActivity.kt`、`AndroidMediaSink.kt`（C11 专用音频能力配置及运行统计）、`LeapmotorClusterMapOffsetTest.kt`、`CarPlayHostDisplaySizeTest.kt`、`AudioClockTrimTest.kt` 和两组 AudioRebuffer 测试（新增回归与构造参数适配）、`mobile/build.gradle.kts`（版本），以及本报告和发布说明。完整工作树增量及文件保存在 `leap/build-snapshots/fix21-source.zip`，基准 commit 为 `e1abc6aa05c60b6bbc29e6c6e599eccc1e70dfe7`；其中包含已有 fix19/fix20 改动，便于还原测试 APK。

车测建议：覆盖安装 fix21，无需清除数据。对比偏移开/关时的左、上渐隐和圆角；播放熟悉的持续人声或钢琴曲 5–10 分钟，检查有无音调漂移。下一份报告应显示 `fade=content-edge`、音乐 `clockTrim=off` 和固定的 `playbackRate=48000`（对 48 kHz 音乐）。若仍有异音，保留播放时间段与日志，以区分剩余的卡顿、声源和 DSP 问题。当前尚未执行实车、设备 instrumentation 或实际音频录制对比。
