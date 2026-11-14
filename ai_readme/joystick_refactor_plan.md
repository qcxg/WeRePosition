# 懸浮搖桿：現況與後續驗證

更新：2026-11-14。本文記錄目前實作；早期「路線頁手動搖桿」的規劃已過時。

## 現況

- `JoystickSettingsScreen` 只設定方向模式、浮動速度上下限，以及啟動或結束懸浮搖桿。自動往返路線仍有獨立入口，並非搖桿的前置條件。
- `FloatingJoystickOverlay` 以獨立、半透明的 overlay window 呈現 128 dp 輪盤與 132 × 36 dp 速度膠囊，兩者維持 8 dp 間距。24 dp 的退出與鎖定按鈕在左右外側，垂直對齊中間空隙。速度膠囊整片可拖，填充寬度表示目前速度；不使用細線加圓點。拖動輪盤外圈會一起移動所有控件，位置以 dp 儲存並限制在安全畫面範圍內；鎖定後外圈不可拖動。
- 輪盤追蹤單一 pointer；按下、拖動、放開或取消只更新控制狀態。100 ms monotonic 節拍計算位移，單次時間差限制在 250 ms 內，避免主執行緒卡住後一次跳遠。
- `NORTH` 模式以螢幕上方為北；`DEVICE` 模式把裝置航向加到搖桿方位。輪盤顯示 N/E/S/W；只有 N 有圓形標記，四個方向都隨裝置航向旋轉。
- `SpoofingService` 擁有執行期 anchor、RF 解析及唯一 runtime writer。搖桿可讓精簡位置 frame 約每 100 ms 更新；`system_server` 在搖桿期間以 500 ms 節拍通過原有 framework provider 管線補報。RF 模擬與位置抖動在搖桿期間暫停，避免逐格查詢與大幅漂移遮蓋操作位移，結束後恢復原設定。
- Android 17（API 37）實機確認：搖桿開啟時運行期 JSON 與 `gsm.wrp.enable_jitter` 都為 `false`，關閉搖桿後按原設定恢復為 `true`。
- 首頁地圖的選點保持搖桿啟動時的位置。搖桿可在系統內移動虛擬位置，期間不改首頁輸入框或鏡頭；關閉搖桿時服務恢復開始時的 anchor。放開輪盤只把速度歸零，不重置已走到的位置。
- 開始新固定點或自動路線會先關閉懸浮搖桿，避免兩個運動 owner 同時控制座標。收起搖桿不停止位置模擬；退出按鈕會停止整個模擬。位置模式未啟用時須五秒內再次點擊才連動啟動。舊 `RouteRunMode.MANUAL` 和未接通的「真實道路規劃」選項已刪除。
- 輪盤與滑條不使用即時跨視窗背景模糊。這項系統合成效果在 Google Maps 等獨立渲染表面上可能造成閃爍；目前使用半透明、依系統色調著色的獨立表面。

## 資料流

```text
輪盤 touch + 裝置航向 + 速度膠囊
  -> FloatingJoystickOverlay 100 ms monotonic 輸入節拍
  -> SpoofingService 積分 WGS-84 anchor / speed / bearing
  -> SpooferProvider in-memory current position
  -> SpoofingService 單一 runtime frame writer / exact-cell RF resolver
  -> system_server framework 定位與已選 App 的直接 RF/GNSS 補充 hook
```

## 轉向回應修正

- 觸控仍只保留最新輸入；每次 100 ms 積分以 conflated wake 喚醒同一個服務 writer，不建立拖動事件的寫入隊列。
- 等待 runtime coordinator 鎖後才讀取最新位置／方向／速度並建立精簡 frame；IO 節拍不再把自己的舊樣本寫回搖桿持有的記憶體位置。寫入完成後也記錄實際發布的樣本。
- 放手同樣喚醒 writer，未變動的靜止位置只保留 heartbeat。已送出的 root 命令仍等待確認完成，維持停止屏障與單一 writer。
- 新增實機測試：人為阻塞 writer，向東後轉北，解除後固定第一筆 frame 檢查不重播向東樣本，並測量放手歸零的延遲。

## 邊界

- 沒有第二個 runtime writer、Android test/mock provider、每 App 定位 callback 計時器或 Play 服務作用域。
- RF 開關開啟但當前格沒有 payload 時保持阻斷；虛構 RF 僅在使用者明確啟用後才可生成。
- 地圖六角格按 cell ID 增量更新，鏡頭停下時不重建未變化的 polygon。
- 自動往返路線仍保留。Room 只維護現行 schema；舊版資料庫開啟時直接重建。

## 仍需實機驗證

- 安裝新 APK 並重新載入相應 LSPosed 進程後，檢查 Google Maps 與其他 App 在搖桿顯示、拖動地圖、切換頁面時是否仍閃爍。
- 檢查長時間拖動時距離、速度、方位與實際 100 ms 積分一致；放手後下一個 framework fix 的速度為零。
- 檢查裝置旋轉時 N 標記及相對方向；跨 RF 六角格時 payload 與位置同步。
- 檢查結束搖桿、停止模擬、重新開始、鎖屏與進程重啟後不會保留移動速度或覆蓋首頁起點。
