# DiPlay「C11 车道级导航版」APK 逆向分析报告

> 目标：对比开源原版 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，找出这个 APK 为零跑做了哪些适配，**重点：方控、投仪表**，并给出可复现的分析方法。
>
> 分析日期：2026-10-11　｜　分析人：WorkBuddy　｜　全部结论均基于本地解包产物，证据路径可复核
>
> **归属说明**：本文只是本仓库零跑适配补丁的**证据底稿**。所有源码版权归上游 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）；
> 本仓库的贡献仅为把下面识别出的零跑行为重新实现并提交。补丁实现以 [`LEAPMOTOR.md`](LEAPMOTOR.md) 为准，本文保留原始推导过程。

---

## 一、结论速览

这个 APK **不是原版 DiPlay 的官方构建**，而是基于原版 v0.2.15 源码的一个**闭源分支/定制版**，核心证据：

| 判定项 | 证据 | 结论 |
|---|---|---|
| 源码同源 | 根工程名 `xcertplay`、包名前缀 `com.shilapi.xcertplay`、`minSdk 25 / targetSdk 37 / compileSdk 37`、`abiFilters arm64-v8a+armeabi-v7a+x86_64` 与原版 `shared/build.gradle` **完全一致** | ✅ 确系同一代码库 |
| 版本基线 | `versionName 0.2.15`、`versionCode 31`；原版 tag `v0.2.15` 提交 `b940efe` | 基线 ≈ v0.2.15 |
| 非官方构建 | Manifest `package="com.shihab.diplay.psabeta"`，而原版无任何 `psabeta` flavor（`productFlavors` 搜不到） | ⚠️ 定制风味 |
| 零跑代码 | 新增 `LeapmotorMediaKeys` / `LeapmotorMediaProtocol` / `LeapmotorGearMonitor` 三个类；`com.leapmotor.*` 广播 **33 个**；而原版 v0.2.15 与 main 分支**均无**任何 `leapmotor` 代码 | 🎯 全部为零跑新增 |

**零跑适配共 6 处，分成 4 组：**

1. **方控（方向盘按键）** — 最大改动，4 条广播通道 + 33 个 action + 二进制/JSON 双解析 + 250ms 去重
2. **投仪表（仪表盘投射）** — 用 `Build.FINGERPRINT` 识别 C11 车机，为 1920×720 仪表屏定制 720×720 方形视口
3. **档位联动（倒挡自动暂停 CarPlay）** — 监听 CAN 广播，挂 R 挡暂停、退出恢复
4. **CAN 服务心跳 / 升级询问** — 周期向 `com.leapmotor.carcanserver` 发 `upgradeAsk`

---

## 二、APK 基础画像

```
文件    : DiPlay-C11车道级导航版-v0.2.15-245.apk   16 MB (17,073,639 B)
包名    : com.shihab.diplay.psabeta          (代码包名 com.shilapi.xcertplay)
版本    : versionName 0.2.15 / versionCode 31
SDK     : minSdk 25 (Android 7.1) / targetSdk 37 / compileSdk 37
Dex     : 6 个 (classes.dex ~ classes6.dex)，共约 35 MB
ABI     : arm64-v8a, armeabi-v7a, x86_64 (另有 x86 仅含 androidx.graphics.path)
so      : libxcertplay_i2c.so / liblocal_hotspot_radio.so / libspeex_echo.so
assets  : byd-hud-icons/ (48 个转向图标 png + LICENSE-BYDMate.txt/NOTICE.txt)、offline-mfi/ (MFi 证书)、navigation_test.pcm、dexopt/
权限    : 25 项，含 INSTALL_PACKAGES、MANAGE_USB、SYSTEM_ALERT_WINDOW、PACKAGE_USAGE_STATS 等高危项
```

**关键组件（Manifest）：**

| 组件 | 作用 |
|---|---|
| `WheelKeyService` | 无障碍服务，拦截方向盘物理按键（原版即有） |
| `UsbAutoConfirmService` | 无障碍服务，自动确认 USB 授权弹窗 |
| `MapEmbedService` | 对外暴露地图嵌入服务 |
| `DiPlaySessionService` | 前台服务（location/connectedDevice/microphone） |
| `AdbClusterActivity` | `allowEmbedded=true`，仪表投射容器 Activity |
| `BootReceiver` / `PhoneBluetoothReceiver` | 开机自启 / 蓝牙事件 |

**注意**：`libxcertplay_i2c.so` 是原版自带的 Linux I2C JNI（`shared/src/main/jni/linux_i2c_jni.c`），用于 iAP2 有线握手，**不是**零跑适配。

---

## 三、分析方法（可复现）

### 3.1 工具链

```bash
brew install apktool            # 本例 Apktool 3.0.2
# Android build-tools 已有：~/Library/Android/sdk/build-tools/37.0.0
# 另需：unzip、strings、git、keytool（macOS 自带）
```

### 3.2 完整步骤

```bash
WORK="./work"
mkdir -p "$WORK" && cd "$WORK"
cp "<你的 APK 路径>/DiPlay-C11车道级导航版-v0.2.15-245.apk" base.apk

# ① 解包（保留 dex）→ 看 Manifest / res / assets / lib
apktool d -f -s -o apktool_smali base.apk

# ② 全量反编译 dex → smali（本次分析的主力证据）
apktool d -f -o smali_full base.apk

# ③ 提取全部字符串，做关键词扫描（最快定位厂商适配）
cd apktool_smali && for f in classes*.dex; do strings -n 4 "$f"; done > ../all_strings.txt

# ④ 拉取原版基线（注意 git 全局代理若失效，用环境变量代理）
git -c http.proxy=$http_proxy -c https.proxy=$https_proxy clone --depth 1 \
    https://github.com/shihabal3amri/DiPlay.git DiPlay_main
git -c http.proxy=$http_proxy -c https.proxy=$https_proxy clone --depth 1 \
    --branch v0.2.15 https://github.com/shihabal3amri/DiPlay.git DiPlay_v0215

# ⑤ 双向验证：原版里有没有？APK 里有没有？
grep -ril 'leapmotor' DiPlay_v0215/      # → 只有 docs 里的一条 issue 记录，无代码
grep -i 'leapmotor' ../all_strings.txt   # → 大量命中

# ⑥ 类级别 diff（Kotlin 文件级类有 Kt 后缀，需归一化）
find smali_full -path '*com/shilapi/xcertplay/*' -name '*.smali' \
  | sed 's#.*/##; s#\.smali$##; s/\$.*//' | sort -u | sed 's/Kt$//' | sort -u > apk_classes.txt
find DiPlay_v0215/{shared,common,automotive,mobile}/src/main -name '*.kt' \
  | sed 's#.*/##; s#\.kt$##' | sort -u > base_classes.txt
comm -23 apk_classes.txt base_classes.txt    # APK 独有类
```

### 3.3 本报告使用的判据

- **"零跑新增"** = APK 中有、且原版 v0.2.15 **和** main 分支都搜不到 → 硬证据
- **"原版自带"** = 原版源码中存在同名类/字符串 → 避免把 BYD 能力误判成零跑适配
- 所有结论均落到具体 smali 行号 / 常量值，可逐条复核

---

## 四、零跑适配清单

### 🎯 A. 方控（方向盘按键）— 改动最大

新增文件：`smali_classes5/com/shilapi/xcertplay/LeapmotorMediaKeys.smali`、`LeapmotorMediaProtocol.smali`（同源于 `LeapmotorMediaKeys.kt`，431 行）
注入点：`CarPlayHostActivity.smali:31148-31162`（`attach(context, controller)`，随 CarPlay 会话启停）

> **补充更正**：经进一步核对，`LearnedWheelKeys`（源 `WheelLearning.kt`，549 行）在上游 v0.2.15 与 main 中**均不存在**（`git grep` 计数为 0），它同样是该分支引入的，属于"方控按键学习"功能，详见移植包 §7。上面这段描述仅对 `WheelKeyService` 成立。

#### A1. 四条广播通道

| 通道 | 常量 | action 数 | 解析方式 |
|---|---|---|---|
| **CHANNEL_A** 主通道 | `CHANNEL_A` | 31 | 从 `byte[] receiver` 或 `String action` 取动作名 |
| **STOCK_HOUSEKEEPING** 车机自用 | `STOCK_HOUSEKEEPING` | 5 | 被 `isWheelAction()` 拦截，**不下发给 CarPlay** |
| **EXTRA_CHANNELS** 扩展通道 | `EXTRA_CHANNELS` | 2 | 读 int extra：`ICU_MediaSwitch`/`mediaSwitch`、`ICU_MediaKey`/`mediaKey` |
| **C11_CHANNEL** C11 专属 | `C11_CHANNEL` | 2 | 读 `keyEventType` + `value` 字符串 |

**CHANNEL_A 全部 31 个 action**（`LeapmotorMediaProtocol.smali:148-211`）：

```
car.meter.music.BROADCAST            car.hmi.music.BROADCAST
com.leapmotor.command.music          com.leapmotor.command.multimedia
com.leapmotor.command.media          com.leapmotor.command.playpause
com.leapmotor.command.next           com.leapmotor.command.previous
com.leapmotor.command.key            com.leapmotor.command.mediaKey
com.leapmotor.multimedia.mediaKey    com.leapmotor.multimedia.MediaKey
com.leapmotor.media.keyevent         com.leapmotor.media.KEY_EVENT
com.leapmotor.audio.key              com.leapmotor.audio.KEY_EVENT
com.leapmotor.audiocenter.mediaKey   com.leapmotor.vehicle.keyevent
com.leapmotor.vehicle.mediaKey       car.hmi.media.KEY_EVENT
car.hmi.keyevent                     car.media.keyevent
car.media.next                       car.media.previous
com.leapmotor.ICUMainCtrl            com.leapmotor.ICUCtrl
com.leapmotor.customkey.media.next   com.leapmotor.customkey.media.prev
com.leapmotor.customkey.media.pauseplay
com.leapmotor.customkey.music.next   com.leapmotor.customkey.music.prev
```

> 一次性注册 31 个 action，说明**作者无法确认 C11 各固件版本实际发哪一个**，采用了"广撒网 + 去重"的兜底策略。这是逆向适配的典型特征。

**STOCK_HOUSEKEEPING 5 个**（车机内部命令，需屏蔽）：
`car.meter.query.BROADCAST`、`com.leapmotor.pohone.toxmly.calling`、`com.leapmotor.command.netRadio`、`com.leapmotor.command.fmradio`、`com.leapmotor.music.netradio.search`

#### A2. 三种载荷解析

**① 二进制 payload**（`actionFromPayload`，`:765-830`）
```
byte[0] * 100 + byte[1] = 后续 JSON 长度（自定义长度前缀）
byte[2..] = UTF-8 JSON
```

**② JSON**（`actionFromJson`，`:557-763`）
```json
{ "src": "...", "type": "music", "data": { "action": "nextOne" } }
```
- `src == "diplay"` → **直接忽略**（防自己发的广播回环）
- `type != "music"` → 忽略
- 取 `data.action`

**③ int extra**（`extraChannelAction`，`:1035-1104`）
```
ICU_MediaKey / mediaKey      == 1 → playpause
ICU_MediaSwitch / mediaSwitch== 1 → preOne
ICU_MediaSwitch / mediaSwitch== 2 → nextOne
```

**④ C11 专属**（`c11ChannelAction`，`:832-1033`）
```
action ∈ { com.leapmotor.action.METER.CTRL, com.leapmotor.action.VOICE.CTRL }
keyEventType ∈ { "steeringWheel", "backRow" }     ← 同时支持后排按键
value: "1" → preOne    "2" → nextOne    "3" → playpause
```

#### A3. 动作 → CarPlay 媒体键映射

`CarPlayMediaButton.forLeapmotorAction()`（**在已有类上新增的方法**，`airplay/CarPlayMediaButton.smali:198-485`）：

| 归一化后的动作串（小写） | 返回的媒体键码 |
|---|---|
| `play` | **1** = PLAY |
| `pause`, `stop` | **2** = PAUSE |
| `playpause`, `pauseplay`, `toggleplay`, `toggleplaypause`, `play_pause` | **3** = TOGGLE |
| `next`, `nextone`, `nexttrack` | **4** = NEXT |
| `prev`, `preone`, `previous`, `previoustrack` | **5** = PREVIOUS |

`isWheelAction()` 负责过滤车机内部命令；被过滤时打印 `media key ignored: car-internal command source=...`。

#### A4. 防抖与优先级

- **250ms 去重窗口**：`DUPLICATE_WINDOW_MILLIS = 0xfa`，同一按键 250ms 内重复到达直接丢弃（`:150-188`）
- **优先级链**（`dispatch`，`:986-1416`）：
  1. `LearnedWheelKeys.onKey("CAR:" + action)` — 若正处于"学习模式"则优先录入
  2. `LearnedWheelKeys.broadcastBindingConsumes(action)` — 已被学习的按键占用则忽略
  3. `LearnedWheelKeys.learnedCarButton(action)` — 学习过的自定义按键
  4. `CarPlayMediaButton.forLeapmotorAction(action)` — 零跑协议映射
  5. 优先投给 `CarPlayVideo.onMediaKey()`（本地视频播放器），否则 `CarPlayController.sendMediaButton()` 发给 iPhone

---

### 🎯 B. 投仪表（仪表盘投射）

改动位置：`smali_classes5/com/shilapi/xcertplay/ClusterMapPresentation.smali` 及其 `$Companion`

#### B1. C11 车机识别

```java
// ClusterMapPresentation.smali:224
IS_LEAPMOTOR_C11 = Build.FINGERPRINT.contains("qti/msmnile_gvmq", ignoreCase = true);
```
`qti/msmnile_gvmq` = 高通 **MSMNILE**（SA8155P 车规平台）+ GVMQ 板型，即零跑 C11 车机指纹。全 APK 仅此一处指纹判定。

#### B2. 仪表视口定制

```java
// ClusterMapPresentation$Companion.smali:1112-1155
public ClusterMapViewport leapWindow(int w, int h) {
    if (IS_LEAPMOTOR_C11 && w == 1920 && h == 720) {   // 0x780 x 0x2d0
        return new ClusterMapViewport(
            /*left*/ w - 720 = 1200, /*top*/ 0,
            /*w*/ 720, /*h*/ 720,
            /*srcL*/ 0, /*srcT*/ 0, /*srcW*/ 720, /*srcH*/ 720,
            false, true);
    }
    return null;
}
```

**含义**：C11 仪表是一块 **1920×720 长条屏**，CarPlay 的仪表流是**正方形**的，所以取右侧 **720×720 方形区域**（x 偏移 1200，右对齐），源也按 720×720 采样，保证不变形。

**调用链**（`ClusterMapPresentation.smali:440-486`）：
```
DiLink51ClusterLayout.supported()  ── 命中 ──> BYD DiLink 5.1 布局（原版路径）
        │ 未命中
        └──> leapWindow(w, h)  ──> 零跑 C11 方形视口（新增回退）
```
即：**零跑 C11 走的是原版 BYD 仪表布局失败后的兜底分支**。

#### B3. 仪表屏探测

`probeClusterDisplay()` 从 `DisplayManager` 里按名称关键字找仪表屏（`ClusterMapPresentation.smali:287`）：
```java
listOf("cluster", "instrument", "dashboard", "meter")
```
这是原版通用逻辑，C11 复用。

#### B4. 投仪表的其余部分是原版 BYD 能力（**不是零跑适配**）

| 原版模块 | 内容 |
|---|---|
| `BydHudBridge` | 通过 **SOME/IP** 发导航指引到仪表：`com.ts.car.someip.service` / `SomeIpServerService`，`SERVICE_ID=0xb010a00010000`、`HUD_TOPIC=0x4010a00018001`，事务码 `START_SERVICE=4 / REGISTER_CALLBACK=1 / FIRE_EVENT=6` |
| `AdbClusterActivity` + `AdbClusterRouter` | ADB 模式下的仪表投射容器 |
| `assets/byd-hud-icons/0x0.png ~ 0x2f.png` | 48 个转向图标（含 BYDMate 许可声明） |
| `BydOemClusterNavi` / `DiLink3ClusterOutput` / `BydClusterSong` | DiLink 仪表导航/歌词输出 |

> ⚠️ 容易误判点：`com.ts.car.someip.*` 里的 **TS 不是零跑**（是车载 SOME/IP 中间件供应商），这套在原版 v0.2.15 里就有（`shared/src/main/java/com/shilapi/xcertplay/hud/BydHudBridge.kt`）。零跑 C11 若支持 HUD 指引，也是**复用**这条 BYD 链路。

---

### 🎯 C. 档位联动（挂倒挡自动暂停 CarPlay）

新增文件：`LeapmotorGearMonitor.smali`（源 `LeapmotorGearMonitor.kt`）
注入点：`CarPlayHostActivity.smali:28075-28082 / 31162`（与方控一同 attach/detach）

| 项目 | 值 |
|---|---|
| 监听广播 | `leapmotor.cantoapp.update`（CAN → App） |
| 档位字段 | int extra **`1010105`**（疑为 CAN 信号 ID 或字典键） |
| 档位映射 | `-1`→未初始化，`1`→**R**（倒车），`2`→**N**，其他值→`3`→**D**，异常→`?` |
| 挂 R 挡 | `CarPlayController.sendMediaButton(2)` → 暂停 CarPlay，日志 `reverse: pause CarPlay sent=true` |
| 退出 R 挡 | `CarPlayController.sendMediaButton(1)` → 恢复，日志 `reverse ended: resume CarPlay sent=true` |
| 轮询线程 | `diplay-gear-poll`（独立守护线程，防止广播丢失） |
| 日志 TAG | `DiPlay-Gear` |

**这是非常典型的车规适配**：挂倒挡时让出音频/焦点给倒车影像与雷达提示音，退出倒挡自动恢复。原版完全无此逻辑。

---

### 🎯 D. CAN 服务心跳 / 升级询问

`LeapmotorGearMonitor.smali:426-440`，在 `runPoll()` 轮询中周期发送：

```java
Intent it = new Intent("leapmotor.apptocan.update");      // App → CAN
it.setPackage("com.leapmotor.carcanserver");
it.putExtra("upgradeAsk", "upgrade");
context.sendBroadcast(it);
```

作用推测：向零跑 CAN 服务周期性宣告/续期自身状态（或请求提升优先级），防止车机收回多媒体控制权。语义未在代码中明示，属**待实车确认项**。

---

### 📦 E. 其他改动（非功能性）

| 项 | 说明 |
|---|---|
| 包名 | `com.shihab.diplay.psabeta`（新增 `psabeta` 风味，原版无） |
| authorities | `com.shihab.diplay.psabeta.fileprovider` / `.diagnostic-reports` / `.update-apks` / `.androidx-startup` |
| taskAffinity | `com.shihab.diplay.psabeta.carplay` / `.cluster` |
| UI 文案 | res 中**搜不到** `leapmotor` / `零跑` / `C11` → 适配完全无感，靠广播 + 指纹自动生效 |

---

## 五、原版自带、不要误判为零跑适配的部分

| 能力 | 归属 |
|---|---|
| `WheelKeyService`（无障碍拦截物理按键）、`WheelZoomKeys`、`WheelJoystick`、`WheelSiriKey`、`NavigationWheelKeyPolicy` | 原版方控基座，零跑分支只是**在其之上**加了广播通道 |
| `LearnedWheelKeys`（`WheelLearning.kt`，549 行按键学习 + UI） | ⚠️ **不是**原版，也是该分支新增（见 §六 更正） |
| `BydHudBridge` + SOME/IP + `byd-hud-icons` | 原版 HUD/仪表输出 |
| `AdbClusterActivity` / `BydOemClusterNavi` / `DiLink3/4/51 Cluster` | 原版 BYD 仪表投射 |
| `libxcertplay_i2c.so` | 原版 iAP2 有线 I2C 握手 |
| `com.byd.*` 全部（amapservice / cluster / automap / diyou …） | 原版 BYD 生态对接 |
| `offline-mfi`（MFi 证书）、`libspeex_echo.so`（回声消除） | 原版 CarPlay 认证与通话降噪 |

---

## 六、待实车验证 / 未确认项

1. **档位字段 `1010105` 的语义** — 是 CAN 信号 ID 还是字典键？值域 `1=R / 2=N / 3=D` 是否覆盖所有固件版本需实车抓 `leapmotor.cantoapp.update` 确认。
2. **指纹 `qti/msmnile_gvmq` 覆盖率** — 若 C11 有硬件批次差异（如 8155 vs 其他），非该指纹的车辆会拿不到 720×720 视口，退化为默认布局。建议核对 `getprop ro.build.fingerprint`。
3. **`upgradeAsk=upgrade` 的确切作用** — 代码只看到发出，看不到响应处理；需抓车机日志确认是心跳、权限续期还是优先级提升。
4. **C11 是否真的走 SOME/IP HUD** — `BydHudBridge` 的 `SERVICE_ID 0xb010a00010000` 是 BYD 的服务号，零跑车机上大概率绑定失败（日志会打 `cannot bind SOME/IP service`）。HUD 转向指引在 C11 上是否生效，需看实车日志。
5. **31 个 action 广撒网的副作用** — 若车机同时发多个同名语义广播，250ms 去重可能吃掉真实按键；反之若车机固件改了 action，方控会整体失效且无降级。

---

## 七、复现与自查命令

```bash
# 在车机上抓零跑广播（验证方控是否真的到达）
adb logcat -s DiPlay-MediaKeys DiPlay-Gear DiPlay-BYD-HUD

# 模拟方控广播（C11 通道）
adb shell am broadcast -a com.leapmotor.action.METER.CTRL \
  --es keyEventType steeringWheel --es value 2

# 模拟倒挡
adb shell am broadcast -a leapmotor.cantoapp.update --ei 1010105 1

# 查看车机指纹，确认是否命中 C11 分支
adb shell getprop ro.build.fingerprint | grep -i msmnile_gvmq
```

---

## 附：证据文件索引

| 内容 | 路径（相对 `work/`） |
|---|---|
| Leapmotor 协议/通道定义 | `smali_full/smali_classes5/com/shilapi/xcertplay/LeapmotorMediaProtocol.smali:138-336` |
| 方控接收与分发 | `smali_full/smali_classes5/com/shilapi/xcertplay/LeapmotorMediaKeys.smali:189-1416` |
| 零跑动作→媒体键映射 | `smali_full/smali_classes5/com/shilapi/xcertplay/airplay/CarPlayMediaButton.smali:198-485` |
| 档位监控 | `smali_full/smali_classes5/com/shilapi/xcertplay/LeapmotorGearMonitor.smali:180-957` |
| C11 投仪表视口 | `smali_full/smali_classes5/com/shilapi/xcertplay/ClusterMapPresentation.smali:224,287,440-486` + `$Companion.smali:1101-1155` |
| 注入点 | `smali_full/smali_classes5/com/shilapi/xcertplay/CarPlayHostActivity.smali:28075,30077,31148,31162,13662` |
| SOME/IP HUD（原版） | `smali_full/smali_classes5/com/shilapi/xcertplay/hud/BydHudBridge.smali:119-125,343-440` |
| 原版基线 | `/tmp/DiPlay_v0215`（tag v0.2.15）、`/tmp/DiPlay_upstream`（main） |
