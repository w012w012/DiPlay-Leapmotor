# C11 fix19 地图画面偏移测试

本版本在 fix18 基础上增加一个可恢复的画面位置实验。720×720 地图视频向右移动 108 像素、向下移动 72 像素，比例保持 1:1；投屏窗口、渐隐边界和发给 iPhone 的安全区域配置保持原值。独立绘制的转向、音乐、速度卡片保持原位。

## 装车对比

1. 覆盖安装 `Diplay-0.2.20-C11-fix19.apk`，无需清除数据。应用内版本为 `0.2.20-C11.19`。
2. 在“设置 → 高级 → 仪表盘上的CarPlay地图（实验性）”中确认渐隐模式为“透明渐变（推荐）”。如果切换了渐隐模式，重新启动 DiPlay 后再连接 CarPlay。
3. 用高德规划固定路线并进入全览。保持现有地图尺寸、渐隐宽度和路线不变。
4. “仪表画面右下偏移（实验性）”默认开启。关闭可立即回到原位，重新开启可立即应用右移/下移；开关本身无需重连。
5. 对比同一路线的道路背景、整条路线和起终点是否一起向右下移动。观察偏移后的全览是否更居中，以及起终点是否被右侧或底部裁掉。

左上可能露出更多透明区域，右侧和底部的内容可能被裁切。这是整幅地图视频平移的结果；本次不改变地图比例尺。看见整体平移，证明本地像素变换路径生效，最终位置和适用路线仍需实车调整。

## 无可见变化时

导出诊断报告，查看 `clusterProjectionPixelOffset`。有效的透明渐隐路径在 720×720 下应包含：

```
leapmotor pixelOffset enabled=true renderer=TextureView view=720x720 right=108.0px down=72.0px scale=1.0 fade=fixed
```

关闭开关后应为 `enabled=false`、`right=0.0px`、`down=0.0px`。若为 `applied=false renderer=SurfaceView requires=alpha-fade`，当前运行的渲染器未使用透明渐隐；调整渐隐模式并重新启动 DiPlay 后再试。

自动化覆盖验证实际 Presentation 的纹理矩阵、开关实时恢复、大小变化时的比例、停止/重新显示时的订阅清理，以及设置控件的中/阿文大字体布局。Amap、iPhone 和 C11 的实际视觉结果仍需上述装车对比验证。

## 本地交付验证（2026-10-15）

在 `leap` 目录执行 `pwsh -NoProfile -File .\build-release-apk.ps1 -Name fix19`，结果为 `BUILD SUCCESSFUL in 5m 15s`。脚本执行仓库要求的完整检查及 Release 打包：

```
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mobile:assembleRelease --console=plain
```

| 单测模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared | 1437 | 0/0/0 |
| common | 1302 | 0/0/0 |
| home | 6 | 0/0/0 |

三个 Debug Lint 与 Debug APK、mobile Release APK 均通过。Lint 报告仍包含非阻断警告（mobile 18 条、home 7 条、maphost 1 条），包括既有未使用资源、调试界面字符串和依赖中的 TrustAllX509TrustManager 提醒；编译还有既有弃用 API、Gradle 和 Java native-access 提醒。本次未扩展网络信任或权限。未执行仪表实车或设备 instrumentation 测试。

构建脚本核对了本地认证资源；`apksigner verify` 通过 v2 签名验证，并额外确认签名证书与 fix17 相同，可覆盖安装。APK 清单为 `com.shihab.diplay`、`versionCode=41`、`versionName=0.2.20-C11.19`，minSdk 25、targetSdk 37。分发文件与构建输出 SHA-256 一致：

```
717824ADB6BE67A87752A72F04696FBA98BE2899D40E66FD7621370BDF15F846
```

`python scripts/check_public_tree.py` 和 `git diff --check` 均通过。新增界面的大字体紧凑/宽屏中文及阿拉伯文截图位于 `common/build/reports/c11-offset-ui/`，已检查文字换行与开关布局。

改动涉及 `LeapmotorClusterMapOffset.kt`（独立偏好与纹理平移）、`ClusterMapPresentation.kt`（C11 透明模式生命周期绑定）、`ClusterProjectionState.kt`（诊断快照）、`DiPlayActivity.kt` 和各语言文案（对比开关）、两组偏移测试及 mobile 版本号。
