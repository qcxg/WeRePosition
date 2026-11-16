# Android App 的 MD3e 遷移與配色實作

適用於使用 Kotlin 與 Jetpack Compose 的 Android App；以 WeRePosition 2.1.3 為實作範例，版本資料核對於 2026 年 11 月 17 日。完成遷移需要同時接好主題、顏色角色、互動元件與動態，而不只是增加圓角。UI 遷移不需要 WeRePosition 的 Root、LSPosed、位置服務或特殊權限。

可直接複用的起點是 [範例目錄](examples/md3e/)：`BrandColors.kt` 提供完整明暗色盤，`AppTheme.kt` 接入系統動態取色與 Expressive 主題，`SettingsToggle.kt` 示範帶勾／叉的開關列。這些檔案使用獨立的 `md3e.example` 包名，移入另一個 App 時一併調整。

## 依賴與架構

本次使用官方 `androidx.compose.material3:material3:1.5.0-rc01`。它是 Release Candidate；當日官方穩定版仍為 1.4.0。各 App 應鎖定採用的版本，升級時重新檢查 API，勿使用 `+` 動態版本。[官方發布記錄](https://developer.android.com/jetpack/androidx/releases/compose-material3)

WeRePosition 的可重現組合是 Compose BOM `2026.09.00`、Material 3 `1.5.0-rc01`、Kotlin 與 Compose compiler plugin `2.2.10`、AGP `9.1.1`、Java 21、`compileSdk=37`。此 RC 的 Android AAR metadata 要求至少 `compileSdk=37`、AGP `9.1.0`；其他 App 的工具鏈也須滿足這個下限。BOM 與明確指定的 Material 3 版本分開管理：前者不會自動替你選擇這個 RC。

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended")
}
```

Compose App 可直接替換主題及元件。XML／View App 可先用 `ComposeView` 導入單頁，保留原有導航；Flutter、React Native 等架構須使用相應平台元件或接入原生 Compose 頁，不能直接使用這份 Kotlin 主題。Android 系統版本與 UI 元件庫版本也是兩回事：系統動態取色需要 API 31，低版本使用品牌色盤；WeRePosition 自身的最低 API 34 並非所有 MD3e App 的要求。

## 取色來源

### 系統動態取色

以 `dynamicLightColorScheme(context)`／`dynamicDarkColorScheme(context)` 取得系統提供的 Material 配色。App 使用的是系統色盤，無須讀取桌布影像，也不應為這項功能額外要求檔案或照片權限。系統／使用者選用低彩度色盤時，灰色或柔和色調是合理結果。[Compose 主題說明](https://developer.android.com/develop/ui/compose/designsystems/material3)

`AppTheme.kt` 會在 API 31 以上、動態色開啟時走這條路徑；其他情況使用 `BrandLight`／`BrandDark`。它觀察明暗模式、設定值與 `LocalConfiguration`。若 App 自行攔截設定變更或保留長生命週期色盤快取，須在恢復畫面時同步主題狀態，避免桌布／系統色盤已變更，畫面仍用舊快取。

將「使用系統配色」保存於 DataStore 或既有設定層，再以 Flow／Compose State 或 SharedPreferences listener 驅動重組。只寫入偏好設定、不更新 UI 狀態，畫面不會可靠地即時換色。

### 品牌或使用者選色

以不透明的 `#RRGGBB` 作為種子，再產生完整淺色與深色色盤。可使用 [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) 匯出，或使用範例內的產生工具。範例以青綠色 `#006A6A`、官方 Material Color Utilities `0.4.0`、`SchemeTonalSpot` 與標準對比產生。

```powershell
# 在其他專案的工作目錄執行；需要 Node.js 24。
npm install --prefix .md3e-tools --ignore-scripts --no-audit --no-fund @material/material-color-utilities@0.4.0
node docs/examples/md3e/generate-brand-colors.mjs '#006A6A' .md3e-tools > BrandColors.kt
```

在 Windows、macOS 或 Linux，將腳本位置改為你複製後的實際路徑即可。`.md3e-tools/` 應加入該專案的忽略清單。產生工具只在開發時使用 Node；Android App 只編譯產出的 Kotlin 常數，不增加 Node 或 JavaScript 執行期。腳本也處理 MCU 0.4.0 在未經 bundler 使用時的相對模組副檔名解析，並檢查主要文字／容器配對至少達到 4.5:1。

MCU 使用 HCT 相關色彩演算法建立色調與角色；`SchemeTonalSpot` 是此範例選擇的色盤策略。MD3 Expressive 的元件風格與 MCU 中名為 Expressive 的色盤變體不是同一個開關，無須為了使用 Expressive 元件而強制更換色盤變體。[Material Color Utilities](https://github.com/material-foundation/material-color-utilities)

WeRePosition 現有 `tonedColorScheme()` 仍採自訂 HSV 生成，並覆蓋表面、固定色與反轉色等角色。它是本專案的現行自訂方案，並非官方 HCT 產色器；其他 App 可以直接採用這份 HCT 範例，不必照搬 HSV 公式。這次發布未把 WeRePosition 的自訂取色演算法改成 HCT。

### 從圖片選種子

若其他 App 要從封面或圖片取色，先縮小圖片、排除完全透明像素，再使用 MCU 的量化與評分工具挑選代表色；空圖片或低資訊量圖片應有品牌色備援。只保存選定種子或圖片內容識別，圖片換掉時重新產生，勿在每次 Compose 重組解碼圖片。

圖片代表色只是種子，不宜直接作為文字或整片背景色。取得種子後仍要走同一份明暗角色生成流程。使用者透過 Photo Picker 選圖與系統動態取色是兩個獨立功能，不應把前者的權限需求套到後者。

## 配色角色與對比

每個畫面從 `MaterialTheme.colorScheme` 取色；主題只在 App 根部決定。不要在某個子頁重新建立局部 `lightColorScheme()`，也不要只修改 `primary` 後留下其他預設紫色角色。`BrandColors.kt` 同時輸出 48 個明色角色及 48 個暗色角色，包含固定色與表面階層。

| 角色 | 適合用途 | 內容配對 |
| --- | --- | --- |
| `primary` | 主要提交或啟動操作 | `onPrimary` |
| `primaryContainer` | 重要選取背景、重點資訊 | `onPrimaryContainer` |
| `secondaryContainer` | 次要操作、圖示底塊 | `onSecondaryContainer` |
| `tertiaryContainer` | 補充重點或獨立功能區 | `onTertiaryContainer` |
| `surface` | 一般頁面表面 | `onSurface` |
| `surfaceContainerLowest` 至 `surfaceContainerHighest` | 卡片、分組、浮動工具列的不同層次 | `onSurface` 或 `onSurfaceVariant` |
| `outline`／`outlineVariant` | 清楚邊界／弱分隔線 | 不作主要文字色 |
| `error`／`errorContainer` | 錯誤、需要注意的停止或刪除操作 | `onError`／`onErrorContainer` |
| `inverseSurface` | 需要反轉對比的表面 | `inverseOnSurface` |
| `primaryFixed` 等固定色角色 | 使用元件預期的固定色階 | 相對應的 `on*Fixed` 角色 |

淺色與深色分別生成，避免把淺色主色套在黑底、再以猜測的透明度壓暗所有文字。對比須檢查真正相鄰的前景與背景；一般文字以 4.5:1 為目標，大字以 3:1 為目標。產生器檢查不涵蓋所有圖片、模糊背景或自訂停用態，這些仍須看實際合成結果。[W3C 文字對比說明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)

## 主題與狀態

把 [AppTheme.kt](examples/md3e/AppTheme.kt) 與 [BrandColors.kt](examples/md3e/BrandColors.kt) 放進 UI 主題目錄，在 Activity 的 `setContent` 根部使用：

```kotlin
AppTheme(useDynamicColor = settings.useDynamicColor) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        AppNavigation()
    }
}
```

這段中的 `settings` 與 `AppNavigation` 由接收專案提供；兩個範例主題檔可直接編譯。`MaterialExpressiveTheme` 提供對應的主題預設；必要時只覆寫少量字級或形狀，勿為了套回舊的圓角清單而蓋掉所有 Expressive 預設。宣告了 `AppTypography`／`AppShapes` 並不代表它們已傳入根主題，應追蹤實際呼叫處。

## 互動元件

| 畫面需求 | 本次採用的官方元件 | 遷移時要保留的行為 |
| --- | --- | --- |
| 開關 | `Switch` 加 `thumbContent` | 開啟為勾、關閉為叉，停用態與語意仍由 Switch 處理 |
| 設定分組 | `SegmentedListItem` 與 `ListItemDefaults.segmentedShapes` | 首尾圓角、按壓變形、單一點擊入口 |
| 單選或分類按鈕 | `ButtonGroup.toggleableItem` | 選取狀態由外部持有，窄畫面保留 overflow 入口 |
| 啟動與主要操作 | `Button`／`FilledTonalButton` 加 `ButtonDefaults.shapesFor` | 依按鈕尺寸使用變形形狀，保留載入與停用狀態 |
| 速度或偏好數值 | `SliderState`／`RangeSliderState` 的現行 API | 外部值與拖曳結果同步，數值範圍不隨重組重置 |
| 不確定進度 | `LoadingIndicator` | 決定完成的實際任務仍由狀態層控制 |

以上 API 以 1.5.0-rc01 為準。依編譯器要求在局部加入 `ExperimentalMaterial3ExpressiveApi` 等 opt-in；不要用全專案抑制所有警告代替遷移。

### 帶勾與叉的開關

參見 App 內的 [ExpressiveSwitch.kt](../app/src/main/java/com/shiraka/wereposition/ui/components/ExpressiveSwitch.kt)。共用元件的核心是：

```kotlin
Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    thumbContent = {
        Icon(
            if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
            contentDescription = null,
            modifier = Modifier.size(SwitchDefaults.IconSize)
        )
    }
)
```

圖示是狀態的視覺提示，`contentDescription=null` 可避免 TalkBack 重複朗讀「勾／叉」。仍要提供開關對應的文字標籤，並保留 checked 語意。不要把開關、Checkbox、多選列及單次操作按鈕全部改成同一種控件。

若整列可點，列與開關只能有一個狀態變更入口；獨立的 [SettingsToggle.kt](examples/md3e/SettingsToggle.kt) 使用整列 `Modifier.toggleable(role=Role.Switch)`，內部 Switch 使用 `onCheckedChange=null`。列持有 checked 狀態及標籤語意，避免再產生第二個獨立的無障礙操作節點。

### 分組列與按鈕

設定列用 `SegmentedListItem(onClick=..., shapes=ListItemDefaults.segmentedShapes(index,count))`，標題放在新版 `content` lambda。使用元件本身的點擊處理，避免再疊一層 `Modifier.clickable`。

按鈕群組的 checked 值由畫面狀態決定。單選群組可在 `onCheckedChange` 收到 true 時才選新項目，避免把唯一選項取消成零選取。長標籤及過多項目應能從 `ButtonGroupDefaults.OverflowIndicator` 到達，不能單純截掉尾端分類。

主按鈕可以使用 `shapes=ButtonDefaults.shapesFor(56.dp)` 與 `heightIn(min=56.dp)`。本專案一般操作保留至少 48 dp 的觸控範圍；不要縮小整體可點面積來製造緊湊感。[Compose 無障礙預設](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

滑桿可複用 [ExpressiveSlider.kt](../app/src/main/java/com/shiraka/wereposition/ui/components/ExpressiveSlider.kt)：`rememberSliderState` 保存互動狀態，外部 value 更新 `state.value`，`Slider(state=..., onValueChange=...)` 回報新值。不要在每次數值變動時建立全新 state，否則容易打斷拖曳。上下限滑桿另用 `rememberRangeSliderState`，保持 start 小於 end。

## 模糊表面與導航

本專案的玻璃效果是 Haze 與 Material 色盤組合的自訂表面，並非所有 MD3e 元件都內建模糊。遷移時可以保留這個風格，但應先確定色階與文字對比，再加入模糊。

```text
內容或地圖層     hazeSource(sharedState)
浮動導航或搜尋層 hazeEffect(sharedState) + 表面色 tint + 邊界 + 文字
```

`hazeSource` 與 `hazeEffect` 應屬於同一個設計範圍，避免把工具列自身再錄入背景。來源放在需要被取樣的內容，浮動表面使用同一份 `HazeState`。複用元件可參考 [GlassOverlaySurface.kt](../app/src/main/java/com/shiraka/wereposition/ui/components/GlassOverlaySurface.kt) 與 [FloatingGlassNavigationBar.kt](../app/src/main/java/com/shiraka/wereposition/ui/components/FloatingGlassNavigationBar.kt)。

一般頁面的取樣必須包含背景：使用 `Modifier.hazeSource(state).background(scheme.background)`，不能把背景放在取樣 modifier 外面。底欄的 `hazeEffect` 也應設定 `backgroundColor = scheme.surface`，作為模糊內容後方的不透明底色；它不是將模糊圖片蓋住的面板 tint。若來源帶透明像素，原本清晰的按鈕邊緣會透過模糊層露出來，增加 blurRadius 或 tint 都不能正確修復。[Haze 1.7.2 的底色說明](https://github.com/chrisbanes/haze/blob/1.7.2/haze/src/commonMain/kotlin/dev/chrisbanes/haze/HazeStyle.kt)

WeRePosition 使用 Haze `1.7.2`、18 dp 模糊半徑、0.5 倍輸入取樣與 0.03 噪點。一般玻璃 tint 為 `surface` 的 0.38→0.24 垂直漸層；搜尋或需要較清楚內容時使用 0.88→0.80，邊界為 0.7 dp、`outlineVariant` 的 0.20 透明度。這些是專案設計值，其他 App 應依背景細節及字色調整。[Haze 專案](https://github.com/chrisbanes/haze)

上述 `hazeEffect` 範例對應 1.7.2。Haze 2 已改用模組化 Blur／Glass API；接收專案若採用 Haze 2，應按其官方遷移文件重寫效果呼叫，保留這裡的表面色與對比設計，不要混用兩代 API。

2.1.2 的底欄另以 1 dp 漸層外框、內縮 1.8 dp 的 0.7 dp 反光線及選中膠囊細邊增加層次；不需要加高底欄或增加模糊半徑。外框用主題 outline 色，反光依表面明暗調整透明度，避免深色模式亮邊過白。

模糊不是文字對比的保證。圖片明暗劇變、地圖標籤密集或效果不支援時，使用較不透明的 tonal surface 作為備援。獨立的系統懸浮窗、地圖的獨立渲染表面與一般 Compose 表面有不同的合成邊界，不能假定跨視窗模糊一定可用；本專案搖桿浮窗使用半透明表面，避免即時跨視窗模糊造成閃爍。

浮動底欄不改變內容的可點區域。頁面底部保留與底欄及系統 insets 相應的捲動空間，確保最後一個按鈕可完整捲出。透明面板的空白處若應攔截觸控，要自行消耗事件，避免操作穿透到地圖。

## 動畫與導航

頁面、設定子頁與首次引導共用一套前進／返回規則。WeRePosition 的水平轉場使用 260 ms 滑動與 180 ms 淡入淡出，返回方向反轉；這是本專案的導航設計值，並非 MD3e 強制時間。

導航轉場不能代替狀態恢復。頁面重新建立時，先還原業務狀態，再決定展示哪個頁面；不要用進場動畫反覆啟動背景工作。一般元件優先保留官方的按壓、形狀及選取動態，避免疊加多套動畫，造成視覺延遲。

自訂浮動底欄可讓選中膠囊使用 `spring(dampingRatio=0.78f, stiffness=450f)` 移動，寬度用較穩定的 0.82／500。按壓時僅將內容以 `graphicsLayer` 縮放至 0.94，放開後以 0.64／650 回彈，選中值為 1.025；觸控範圍不跟著縮小。膠囊的位置要限制在底欄內，文字分配寬度保留原本較穩定的動畫，避免回彈時長標籤被裁切。使用 Compose 動畫 API，讓系統動畫倍率與停用動畫設定生效；不要自行建立無限回彈計時器。

## 其他 App 的遷移順序

1. 查明既有框架、Compose／Material 版本、設定層及導航方式，先建立能編譯的基準。
2. 接入 `MaterialExpressiveTheme`，確定系統明暗、自訂品牌色及動態色的選擇規則。
3. 移入完整明暗色盤，逐頁移除硬編碼的紫色、白色卡片或不正確的 `on*` 配對。
4. 建立共用帶勾／叉的開關、分組設定列、主要按鈕及滑桿，再替換各畫面，保留原來的事件與狀態層。
5. 最後加入玻璃導航或搜尋列，檢查 insets、捲動、鍵盤與觸控穿透。
6. 分別確認明暗色、動態色開關、長中文／英文、大字體、橫向、停用態、載入態及 TalkBack 語意。

建置時完成編譯與 lint；測試以原來功能是否仍能完成為主，例如選取分類、修改滑桿、取消對話框、返回頁面和重建恢復。UI 的文字對比、模糊合成與地圖閃爍仍需在實機看畫面。

## 移交其他專案時可用的要求

```text
請先閱讀專案架構與現有主題，依 MD3E_IMPLEMENTATION_GUIDE.md 遷移。
使用相容的官方 Compose Material 3 Expressive 元件，保留現有業務及導航語意。
根主題支援系統動態取色與完整品牌明暗色盤；元件只使用 ColorScheme 語意角色。
全部開關統一為開啟勾號、關閉叉號；保留停用態、標籤與無障礙語意。
分組列表、按鈕群組及滑桿採現行 API，避免重複 clickable 和互動 state 重建。
模糊只用於適合的浮動表面，補足文字對比、insets 和不支援時的 tonal surface。
完成適合該專案的編譯、lint、行為回歸及實機明暗畫面檢查，並列出改動範圍。
```
