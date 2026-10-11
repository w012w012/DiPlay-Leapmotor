# 零跑适配技术说明 / Leapmotor adaptation notes

上游：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）。
本文件描述本仓库**唯一原创的部分**：零跑适配补丁。源文件清单见 [`../README.md`](../README.md)，
逆向推导过程见 [`LEAPMOTOR-ANALYSIS.md`](LEAPMOTOR-ANALYSIS.md)。

> Upstream: [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) (GPL-3.0).
> This document covers the **only original part** of this repository: the Leapmotor patches.

---

## 1. 会话生命周期挂载（必须先做）

零跑的两套监听器都必须跟随 CarPlay 会话生命周期：会话建立才有权接管方控与响应挡位，会话结束必须立刻释放，
否则会抢走车机收音机的按键，或在没连 iPhone 时误发暂停。

上游在同一位置挂载硬件媒体键版 `CarPlayMediaKeys`，零跑这套广播版与之对等，因此复用同一组锚点。
锚点文件：`common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

- **挂载点**：紧跟 `CarPlayMediaKeys.attach(this, next)`
- **卸载点 A**：紧跟 `CarPlayMediaKeys.detach(oldController)`，下一行为 `CarPlayBackgroundSession.clear(oldController, keepOwner = true)`
- **卸载点 B**：紧跟 `CarPlayMediaKeys.detach(oldController)`，下一行为 `CarPlayBackgroundSession.clear(oldController)`

> ⚠️ 该文件在 v0.2.15 → v0.2.17 之间改动很大（+869 / −99）。**不要用行号对**，用上面的语义锚点。
> **两处卸载都要改**，漏一处会导致重连后重复按键或暂停后不恢复。

Android 13+：`LeapmotorMediaKeys` / `LeapmotorGearMonitor` 已按 `RECEIVER_NOT_EXPORTED` 处理 API 33+。
**不要**改成两参数 `registerReceiver`，某些固件上会被系统拒绝，导致整个方控失效。

可选 — 接诊断输出：

```kotlin
LeapmotorMediaKeys.onDiagnostic = { line -> diagnosticLog?.append(line) }
LeapmotorGearMonitor.onDiagnostic = { line -> diagnosticLog?.append(line) }
```

---

## 2. 方控（steering-wheel keys）

**为什么不能用上游方案**：零跑车机的方向盘按键经 CAN → ICU → 广播转发，第三方 App 收不到任何 `KeyEvent`。
所以必须接收广播。

源文件：`common/src/main/java/com/shilapi/xcertplay/LeapmotorMediaKeys.kt`

### 2.1 通道清单

| 通道族 | 数量 | 载荷 | 说明 |
|---|---|---|---|
| `LeapmotorMediaProtocol.CHANNEL_A` | 31 | 字符串 action / `receiver` 二进制 / `action` extra | 主流 Talking。**一次注册 31 个**，因为不同固件版本用哪个 action 并不确定，属于主动兜底 |
| `STOCK_HOUSEKEEPING` | 5 | — | 车机收音机/语音助手自用命令，**必须识别并丢弃**，否则会抢走收音机 |
| `EXTRA_CHANNELS` | 2 | int extra `ICU_MediaSwitch` / `ICU_MediaKey` | 键码走整形 extra，不是文本 |
| `C11_CHANNEL` | 2 | `keyEventType` + `value` | C11 专属；`keyEventType ∈ {steeringWheel, backRow}`，`value` 1=上一首 / 2=下一首 / 3=播放暂停 |

### 2.2 三种载荷解析

1. **二进制 `receiver` extra**：前两字节是长度前缀，`length = b0*100 + b1`，其后为 UTF-8 JSON
2. **JSON**：取 `data.action`；`src == "diplay"` 时丢弃（**防回环**——自己发出的写操作不能再被当成按键）；`type` 非 `music` 时丢弃
3. **int extra**：`ICU_MediaKey == 1` → 播放暂停；`ICU_MediaSwitch` 1 → 上一首，2 → 下一首

### 2.3 去重与分发

- **250 ms 去重窗口**：同一物理键常被多个通道同时投递，窗口内同一映射结果只放行一次
- 优先级：按键学习捕获 → 已学习的绑定 → `CarPlayMediaButton.forLeapmotorAction()`
- `controller == null`（无 CarPlay 会话）时**不动作**
- 本地视频播放器（`CarPlayVideo.onMediaKey`）优先于转发给 iPhone

### 2.4 映射表

追加在 `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaButton.kt`：

| 动作串 | CarPlay 键 |
|---|---|
| `play` | `PLAY` (1) |
| `pause` / `stop` | `PAUSE` (2) |
| `playpause` / `pauseplay` / `toggleplay` / `toggleplaypause` / `play_pause` | `PLAY_PAUSE` (3) |
| `next` / `nextone` / `nexttrack` | `NEXT` (4) |
| `prev` / `preone` / `previous` / `previoustrack` | `PREVIOUS` (5) |

> **关键**：`isWheelAction()` 覆盖的范围**故意小于** `forLeapmotorAction()` —— 前者排除 `play` / `pause` / `stop`。
> 裸的 `play`/`pause`/`stop` 同样来自收音机和语音路径，放行会造成误触。这个不一致必须保留。

---

## 3. 投仪表（instrument cluster projection）

源文件：`common/src/main/java/com/shilapi/xcertplay/LeapmotorClusterLayout.kt`

### 3.1 车型识别

```kotlin
Build.FINGERPRINT.contains("qti/msmnile_gvmq")
```

`msmnile_gvmq` 是高通 SA8155P 车规平台的板级标识。全 APK 中**仅此一处**使用该指纹，可精确定位。

### 3.2 视口几何

```
仪表面板          1920 × 720
CarPlay 仪表流    720 × 720（正方形）
放置位置          right-aligned → left = 1920 - 720 = 1200，top = 0
```

零跑 C11 的仪表是一块长条屏，右侧留出一个正方形区域给导航地图。

### 3.3 接入方式（当前采用窄口径改造）

改造集中在 `common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt` 的 `onCreate`：

```kotlin
val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
// Leapmotor C11 exposes no measurable DiLink layers, so its square window is derived from
// the physical panel size instead. It is only ever used when DiLink planning failed.
val leap = if (plan == null) LeapmotorClusterLayout.plan(size.x, size.y) else null
```

`videoParams` 改为三分支：

```kotlin
val videoParams = when {
    leap != null -> FrameLayout.LayoutParams(leap.width, leap.height, Gravity.TOP or Gravity.LEFT).apply {
        leftMargin = leap.left; topMargin = leap.top
    }
    plan == null -> FrameLayout.LayoutParams(-1, -1)
    else -> FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
        leftMargin = plan.left; topMargin = plan.top
    }
}
```

**为什么这样够用**：C11 的仪表流本身就是 720×720（方对流方形），不需要 TextureView 的裁剪/缩放变换，
改动面小一个数量级。BYD 路径（`plan != null`）行为完全不变。

### 3.4 完整改造路线（未采用）

`ClusterMapViewport.kt` 已在仓库里，供把整条渲染链抽象成统一视口时使用。那需要同步修改
`InstrumentContrastView` 的构造签名（当前接收 `DiLink51ClusterLayout.Plan`），改动面大得多。
窄口径方案验证通过后再决定是否升级。

---

## 4. 挡位联动（gear monitor）

源文件：`common/src/main/java/com/shilapi/xcertplay/LeapmotorGearMonitor.kt`

| 项 | 值 |
|---|---|
| 广播 | `leapmotor.cantoapp.update` |
| CAN 信号 extra | `1010105`（int） |
| 取值 | `-1` = 未初始化（忽略） / `1` = R / `2` = N / `3` = D |
| 心跳 | 每秒向 `com.leapmotor.carcanserver` 发 `leapmotor.apptocan.update`，extra `upgradeAsk=upgrade` |

- 进入 R 档：`controller.sendMediaButton(PAUSE)`，让倒车影像与雷达提示音独占音频
- 退出 R 档：`controller.sendMediaButton(PLAY)`，从原位置恢复播放
- `pausedByReverse` 标志保证只暂停一次、只恢复一次
- 心跳用于维持车机对 DiPlay 多媒体占用状态的认知——只有在被认可为当前音源时暂停才被接受，且状态丢失时没有回调

---

## 5. 版本差异矩阵

决定升级难度的关键清单（对比 v0.2.15 → v0.2.17）：

| 类 / 文件 | 是否改动 | 影响 |
|---|---|---|
| `shared/.../airplay/CarPlayMediaButton.kt` | ✅ 未变 | 直接追加，零风险 |
| `WheelLearning.kt` (`LearnedWheelKeys`) | — | 上游**不存在**，是零跑分支自身的东西，见下节 |
| `common/.../ClusterMapPresentation.kt` | ⚠️ +17 / −10 | `plan == null` 判定结构未变，锚点成立 |
| `common/.../CarPlayHostActivity.kt` | ⚠️ +869 / −99 | 用语义锚点，不要按行号 |

### 关于 `LearnedWheelKeys` 的重要更正

`LearnedWheelKeys`（方控按键自定义学习）**不是上游基类**：

```bash
git grep -c 'LearnedWheelKeys' v0.2.15 -- '*.kt'   # → 0
git grep -c 'LearnedWheelKeys' main    -- '*.kt'   # → 0
```

它是零跑分支自己写的（约 549 行，Capture → 存储 → UI 全套）。闭源构建没有随包发布源码，本仓库暂未提供实现。

好消息是：**方控能用**与**方控能自定义学习**是两件事，可以分开。仓库里的 `LearnedWheelKeys.kt` 是过渡桩，
四个钩子全部返回"没学过任何键"，保证键一定走通用映射。**补齐真实实现后删除该桩文件即可**，没有其它地方按名依赖它。

---

## 6. 实车验证清单

```bash
# ① 会不会命中零跑投仪表分支
adb shell getprop ro.build.fingerprint | grep msmnile_gvmq

# ② 方控有没有收到广播（看哪些 action 真被命中）
adb logcat -s DiPlay-MediaKeys

# ③ 挡位有没有被解析
adb logcat -s DiPlay-Gear
```

必须实车确认的四件事（见 README §3）：

1. 指纹是否命中
2. 38 个 action 里你的固件用哪个
3. 有没有"一次按键换两首"（重复投递）
4. HUD 转向指引：依赖上游 BYD SOME/IP（`com.ts.car.someip.*`），零跑上大概率绑定失败 → **应视为不可用**
