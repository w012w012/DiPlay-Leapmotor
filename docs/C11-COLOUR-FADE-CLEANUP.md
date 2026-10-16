# C11 fix20：移除颜色渐变方案

> 历史 fix20 变更记录。本文不代表当前版本的完整行为；fix24 功能范围见[C11 清理与验证](C11-FIX24-CLEANUP.md)。

透明渐变已经用于解决 C11 仪表地图与原车背景的色差，因此移除原来的颜色渐变方案。设置中保留“透明渐变（推荐）”与“关闭”，左右过渡宽度和圆角行为保持不变。

## 修改范围

- `ClusterMapPresentation.kt`：移除 `ClusterEdgeMaskView`、色带工厂/挂载函数、颜色模式分支、日夜颜色更新与未使用的宽度访问器。
- `AdbClusterActivity.kt`、`CarPlayHostActivity.kt`：清除色带挂载和日夜颜色调用。旧色带也曾挂在备用 ADB Activity 上，因此该路径同样不再绘制这层色带；显示路由规则、视频流和独立卡片保留。
- `ClusterEdgeFade.kt`、`LeapmotorClusterLayout.kt`：保留透明渐变、圆角、shader 缓存；移除颜色模式常量及过时的说明、模式包装函数。
- `DiPlayActivity.kt`、8 个语言资源目录：删除颜色选项和旧的双色方案文案。
- `AirPlayPersistence.kt`：兼容原有偏好值，写入时限定为透明渐变或关闭。
- `LeapmotorC11Test.kt`、地图偏移与设置测试：移除已删除色带的专用测试，增加原始旧偏好值、实际渲染器和设置选项覆盖。
- mobile 版本为 `0.2.20-C11.20`，versionCode 为 `42`。

## 旧设置兼容

偏好键 `leapmotor_cluster_mask_mode` 保留，数值不重新编号：

| 保存值 | fix20 读取结果 |
| --- | --- |
| 0 | 透明渐变 |
| 1（原颜色渐变） | 透明渐变 |
| 2 | 关闭 |
| 其他值或未保存 | 透明渐变 |

已有宽度、地图尺寸、配对与其他设置无需清除。没有修改权限、网络信任或数据库。

## fix19 测试基线

当时的地图偏移测试仍可使用原有 fix19。该 APK 保留在 `leap/dist/Diplay-0.2.20-C11-fix19.apk`，SHA-256 与构建时一致；该实验已在 fix22 移除，见[当前地图说明](C11-MANUAL-MAP-LAYOUT.md)：

```
717824ADB6BE67A87752A72F04696FBA98BE2899D40E66FD7621370BDF15F846
```

fix19 的源码增量、未提交源文件及基准 commit 信息另存于 `leap/build-snapshots/fix19-source.zip`。fix20 的 `LeapmotorClusterMapOffset.kt` 已与该快照逐字节核对一致，仍为右移 108、下移 72 像素的纯平移实验。

## 针对性验证

以下命令实际执行并通过，耗时 37 秒：

```
.\gradlew.bat :common:testDebugUnitTest --tests com.shilapi.xcertplay.LeapmotorC11Test --tests com.shilapi.xcertplay.LeapmotorClusterMapOffsetTest --tests com.shilapi.xcertplay.LeapmotorClusterMapOffsetSettingsTest --tests com.shilapi.xcertplay.ClusterMapPresentationTest --tests com.shilapi.xcertplay.AdbClusterRouterTest --tests com.shilapi.xcertplay.SettingsTranslationsTest --console=plain
```

覆盖包含直接写入旧值 1 后的读取、实际 C11 Presentation 使用透明 TextureView、偏移参数不变、关闭值 2 保持关闭，以及设置对话框只有透明/关闭两项。透明渐变的像素、圆角和缓存测试仍保留。中/阿文大字体紧凑与宽屏截图已检查。

未执行 C11 或其他车机的实车、设备 instrumentation 测试。

## 完整交付验证（2026-10-15）

在 `leap` 目录执行 `pwsh -NoProfile -File .\build-release-apk.ps1 -Name fix20`，结果为 `BUILD SUCCESSFUL in 4m 59s`。脚本执行仓库要求的 shared/common/home 单测、mobile/home/maphost Debug Lint 与 Debug 打包，以及 mobile Release 打包。

| 单测模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared | 1437 | 0/0/0 |
| common | 1304 | 0/0/0 |
| home | 6 | 0/0/0 |

共 2747 项测试通过。Lint 任务通过，仍有既有非阻断警告（mobile 18 条、home 7 条、maphost 1 条），包括未使用资源、调试字符串与依赖中的 TrustAllX509TrustManager 提醒；编译仍有原有弃用 API、Gradle 与 Java native-access 提醒。

本地认证资源核对、`apksigner verify`、`python scripts/check_public_tree.py`、`git diff --check` 均通过。额外核对安装包证书与 fix19 相同，分发副本与 Release 构建输出一致，fix19 原文件未改变。fix20 APK SHA-256：

```
16A61BA36017407A3A1B218B549C0C8CB8C1A23A701112C868367966CD4EC673
```

构建完整日志在 `leap/build-fix20.log`。fix20 源码增量及工作文件快照另存为 `leap/build-snapshots/fix20-source.zip`，便于对应后续实车反馈。
