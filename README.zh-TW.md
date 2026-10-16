# DiPlay — 零跑 C11 適配版

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**本專案是專為零跑 C11 Android 車機適配的 DiPlay 分支。** 它不是面向比亞迪車機的上游版本。系統指紋包含 qti/msmnile_gvmq 時會啟用 C11 專屬程式；其他 C11 韌體版本和其他車型尚未確認相容。

目前版本：0.2.20-C11.24（versionCode 46）。應用程式套件名稱：com.shihab.diplay；最低 Android 版本為 7.1（API 25），target SDK 為 37。

## C11 適配功能

- 透過 USB 或無線連接 CarPlay；無線方式依車機韌體提供車載熱點、Wi-Fi Direct 或現有 Wi-Fi 網路。
- 在 C11 儀表顯示 CarPlay 導航畫面，可調整地圖大小、位置與邊緣漸隱；另保留音樂卡片和轉向提示卡片。儲存版面後重新連線套用。
- 接收 C11 方向盤媒體按鍵廣播，處理播放、暫停和切歌。
- 可選擇讀取 C11 車輛日誌，**預設關閉**。啟用後透過已授權的網路 ADB 讀取檔位、電量、車速和方向盤事件；日誌也可支援倒車時暫停、離開倒車後恢復音樂。一般 CarPlay 連線不需要 ADB。

BYD 專用 SOME/IP HUD、氛圍燈／音樂分析及 BYD 車輛資料路徑已在 C11 版移除或停用；舊的 BYD 設定不會在 C11 重新啟用這些功能。

## 安裝與更新

請將 C11 APK 安裝在零跑 C11 車機，不要安裝到 iPhone。從[本專案 Releases](https://github.com/w012w012/DiPlay-Leapmotor/releases)取得適用的 C11 APK；若沒有合適版本，請參考[建置指南](docs/BUILD.md)。不要改裝比亞迪上游 APK。

本分支和上游使用相同套件名稱，但簽章不同，不能並存，也不能直接互相覆蓋更新。解除安裝會刪除應用程式私有設定與配對資料。

應用程式內的背景更新檢查目前查詢上游 DiPlay Releases，並非本 C11 分支。它可能提示不適用於本分支的 APK。請在 **設定 → 關於 → 背景更新檢查** 關閉此功能，並從本專案 Releases 取得更新。

## 相容性與驗證範圍

這是社群維護的適配版，並非 Apple 或零跑認證產品。C11 功能依賴系統韌體指紋，未確認所有 C11 年款和韌體皆相容。fix24 的自動化檢查記錄在[清理與驗證紀錄](docs/C11-FIX24-CLEANUP.md)，但目前版本**尚未完成實車驗收**；連線、儀表地圖與漸隱、音訊、方向盤操作及長時間使用仍需在目標 C11 確認。

APK 使用實驗性配件身分，其私鑰可從 APK 擷取。未來 iOS 是否繼續接受無法保證；本專案不是 Apple 認證產品，也不應視為安全憑證。

## 文件

- [安裝與連線](docs/INSTALL.zh-TW.md)
- [C11 相容性與限制](docs/COMPATIBILITY.md)
- [C11 地圖大小和位置](docs/C11-MANUAL-MAP-LAYOUT.md)
- [fix24 清理範圍與驗證](docs/C11-FIX24-CLEANUP.md)
- [隱私權與診斷](docs/PRIVACY.zh-TW.md)
- [從原始碼建置](docs/BUILD.zh-TW.md)
- [目前版本說明](docs/RELEASE-NOTES-0.2.20-C11.24.md) · [專案變更記錄](CHANGELOG.md)
- [第三方聲明與授權](docs/THIRD_PARTY_NOTICES.md)

## 回報問題

請提供 C11 年款／車機韌體、Android 版本、iPhone／iOS 版本、連線方式、相關設定、重現步驟與故障時間。可在 **設定 → 診斷 → 儲存診斷報告** 匯出報告；公開前請檢查個人資料，勿附上熱點密碼或認證檔案。[在本專案回報問題](https://github.com/w012w012/DiPlay-Leapmotor/issues)。

## 原始碼與授權

接收端基於 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0）。設定介面與網站改編自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）。散布修改版時請保留相關授權與[第三方聲明](docs/THIRD_PARTY_NOTICES.md)。DiPlay 與 Apple、零跑及比亞迪均無隸屬關係；CarPlay 與其圖示屬於 Apple Inc。
