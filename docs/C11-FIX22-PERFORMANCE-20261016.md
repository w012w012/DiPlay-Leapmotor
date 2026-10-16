# C11 fix22 卡顿排查与 fix23 对照

输入：用户实测的 `leap/log/DiPlay-20261016-125337-016.txt`，约 74 分钟、12,151 行。用户暂未比较断开 DiPlay 前后的整机表现，同时在使用 LeapTool。当前没有连接到这台开发机的 Android 设备；`adb devices -l` 返回空列表。

## 已观察的事实（observed）

### DiPlay 的车辆日志扫描

`LeapmotorGearMonitor.attach()` 在连接启动时启动单例轮询，连接留在后台时仍继续，停止/重启连接才拆除。此前 C11 没有扫描总开关，车辆电量接口对 C11 固定开启，用户不查看读数也会读日志。

- 快速轮询约每秒一次：`logcat -d -t 2000 -v epoch`，经车端两次 grep 和 tail，最多传回 600 行。每次都重新扫描最近的历史窗口。
- 每 15 次快速轮询另读 `logcat -d -t 40000`，筛出电量/车速候选、最多 200 行；不是每 15 秒只读新增内容，实际间隔还包括命令耗时。
- 速度未知时，每 300 次快速轮询另扫描最近 4,000 行做事件 ID 普查。
- 读取结果有消费者：挡位控制倒车时暂停/离开倒车恢复音乐；SOC 给 iPhone 上报车辆电量；挡位/SOC/速度还显示在设置。C11 速度没有接入 iPhone 车速推算。方向盘功能使用独立广播接收器，不依赖这些日志副本。
- 日志复制有去重和数量上限，当前证据没有显示指数放大自身日志。慢轮询未排除 DiPlay 行是去噪改进点，但没有证据证明它造成此次整机卡顿。

补充交叉核对：报告第 46 行的旧 BYD `battery=true wheelSpeed=true` 也仍为开启，连接启动多次记录 `location=enabled+wheel-speed`。此前没有用 C11 车型排除这些不适用的 BYD 服务：启动页会预热 BYD 电量，定位会包装一个 BYD 轮速源（有订阅时约 250ms 轮询），因此只关 C11 logcat 还不够。这是另一组已确认的配置/调用路径，实际 CPU 代价仍未测。

代码入口：`common/.../LeapmotorGearMonitor.kt`、`C11LogcatProtocol.kt`、`LeapmotorVehicleStatus.kt`、`CarPlayHostActivity.vehicleStatusWanted()`。fix23 的真实入口回归测试在旧行为上失败：`Default-off C11 must not create an ADB log reader`，证明此前默认连接确实会创建日志 ADB 客户端。

### 本地 LeapTool 样本

只读静态分析对象为 `c11车机研究/LeapTool-1.0.4.2-113-licensed-release.apk`，SHA-256：

```text
36CFB505E61FF403429A0A06D836C82914D24C491C089CDADB8C80E61B6B2F16
```

现有反编译目录的三份 DEX 与 APK 内对应 DEX 一致，并在 APK 上做了字符串/调用引用索引。没有修改、重打包、安装 LeapTool 或触碰其授权机制；此处只交付读取方式的静态证据，不能据文件名判断当前安装版本。

- `VehicleEventService` 在自己的授权条件通过后启动 `com.car.leaptool.autotask.monitor.i`。`s2.c0.i()` 构造 `logcat -v epoch -T <时间戳> *:S`，附加约 22 个标签过滤，包括 `C11CarSomeIp:D`、`C11CarXml:D`、`C11SmartContrl:D`、`MediaTlog:D`，与 DiPlay 输入重叠。
- `android.support.v4.media.o.run()` 的对应分支启动一个进程，用 8192 字符 BufferedReader 阻塞 `readLine()`，持续接收新日志；并非每秒执行一次历史 dump。EOF/错误后若仍需监听，约 100ms 后重试；服务销毁/stopMonitoring 会停止。没有绑定 CarPlay 状态。
- `NavigationSyncService` 另有带标签的连续导航日志流（`ModuleRouteServiceImpl:D SomeIpUtils:I *:S`），仅在该服务和同步功能实际启用时运行。
- `amap_log_record_enabled` 默认 false；若用户手动开启，高德日志记录使用没有标签过滤的 `logcat -v time -T 1`，在应用中筛关键词并逐条写入、flush。这是额外的条件性负载，不能假定当前已经开启。
- 另有 `logcat -d -t 4000` 的补种/组件查询，未确认实际调用频率，不将其算成常驻轮询。

原始证据在现有 `_analysis/leaptool_src/sources/` 下的 `s2/c0.java:41`、`android/support/v4/media/o.java:141,186,192`、`com/car/leaptool/service/VehicleEventService.java:64,112`、`com/car/leaptool/autotask/monitor/i.java:273,952,1002`。导航相关在 `NavigationSyncService.java:92`、`navigation/i0.java:1236`、`navigation/f.java:67`、`navigation/ui/NewMapSettingsActivity.java:1100`。

### 视频链路异常

共有 349 条“backlog kept growing or exceeded hard limit”恢复、2 条队列溢出恢复、2 条输入停滞恢复。按 stream 统计窗口累加，主流恢复 345 次、副流 8 次。恢复会释放/重建解码器并请求关键帧。主流有 580 个约 5 秒窗口，89 个窗口的 queue-to-output p90 超过 200ms，最大 605ms；这不是纯 CPU 解码耗时。

按统计窗结束时的 Surface 状态分组，SurfaceView 存在与缺失要分开解释：394 个主 Surface 存在窗口中，85 个显示少于 20fps；186 个 Surface 缺失/离屏窗口中，180 个显示少于 20fps，后者低 shown 本身是预期行为，不能全计成前台卡顿。Surface 缺失时仍收到平均约 35fps，主视频转接到同分辨率 ImageReader，持续解码后丢弃图像；最长缺失时段约 22 分钟（11:59:12–12:21:11）。这是确定的额外后台工作，但 CPU/GPU 代价未测。

日志使用 Qualcomm 硬件 HEVC，主视频 1920×1080，仪表副流 720×720；没有证据证明走软件解码。请求 60fps，实际接收帧率中位数约 34fps。最近上游合并对媒体文件的改动集中于音频，视频离屏保留和平滑策略更早就已存在。

## 推断与尚未验证（inferred / unverified）

两边日志服务同时运行时，会叠加读取、筛选和解析工作，DiPlay 的重复历史扫描尤其值得先去掉。不能把两个 reader 简化为性能开销翻倍，也不能仅凭静态代码认定是整机卡顿主因。当前未测各应用、logd、adbd、系统界面、媒体服务的 CPU，也没有系统温度/频率、GPU 或整机帧时间。

待对照的预测：

1. 若重复日志扫描或不适用的 BYD 车辆服务读取是主要负担，保持 LeapTool、路线和视频设置不变，只关 DiPlay 车辆日志读取并重连，应改善整机响应。
2. 若主视频解码/恢复是主要负担，日志读取关闭后异常仍会存在，且可能随主屏前台/后台、仪表副流状态变化。
3. 若 LeapTool 的额外高德记录或其他系统服务是主要负担，关 DiPlay 扫描仍不够，需在另一组对照中单独改变该服务。

## fix23 的范围

用户已明确选择“默认关闭，需要时再开启”，并已告知会失去基于日志的倒车暂停/恢复、电量和速度。新增 Advanced 内独立 C11 车辆日志卡，默认 false；旧版本没有此键，覆盖安装后也默认关闭，无需清数据。保存只显示重连提示，不主动中断会话。

连接启动时缓存开关；关闭后在 `pollLogcat` 入口返回，不加载 ADB 密钥、不建立日志 ADB 客户端，也不执行快速、慢速和普查命令。被动广播、独立方向盘广播和多媒体占用心跳保留。C11 的电量能力和 provider 同步关闭。另外对 C11 排除旧 BYD 电量预热和轮速源，忽略这些不适用的已保存开关，不删除偏好；GPS 定位继续使用普通来源。非 C11 保留原有 BYD 行为，蓝牙、Wi-Fi 和仪表的恢复清理仍执行。偏好可备份；报告同时标明保存值和当前 reader 状态，避免把下一次连接设置当作当前状态。

新增 `RuntimePerformanceDiagnostics`，仅 C11 host 生命周期内运行：每 5 秒最多一条主线程探针，每 30 秒后台读取进程 CPU、Java/native 已分配堆、系统可用内存，直接写有界异步日志；不运行 shell、枚举进程或逐帧采样。主线程卡住时不会堆积探针，关闭时取消两条队列且不等待原生/磁盘调用。CPU 单核满载记为 100%，多线程可超过 100%；它不含外部 logcat/adbd、系统媒体服务或 GPU 的负担。堆数值也不是进程 RSS 或视频缓冲区总内存。

本轮不改视频 parking、帧率、解码恢复阈值或重新开启后的日志扫描频率，便于先对照去掉无用车辆读取的效果。fix21 的固定音频播放速率保留；本次 fix22 日志 416 条媒体速率均为 48,000Hz。

## 下一轮车测

覆盖安装 fix23 后重新建立连接，保留当前 LeapTool 与视频/路线设置，先保持默认日志读取关闭，观察同样场景的整机响应并导出报告。报告应出现 `vehicle log reader=disabled` 和每 30 秒 `PERFORMANCE sample`；未见某条记录不能单独证明没有负载，需结合会话持续时间和日志轮转。

若仍卡顿，再单独关闭 LeapTool 做第二组对照；不要同时改帧率、视频模式、地图布局和多个后台服务。每组保持数分钟，并记录开始时间及卡顿时刻；这样才能对齐视频恢复、CPU 和主线程延迟。若只愿意做一组，先运行默认关闭日志读取的 fix23，即可验证当前最明确的重复工作是否与症状相关。

尚无实车改善结论，不宣称卡顿根因已经修复。APK/完整 CI 结果记录在手动地图文档的 fix23 交付检查中。
