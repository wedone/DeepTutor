# DeepTutor Android 客户端（WebView 壳）设计

- 状态：待评审
- 日期：2026-11-11
- 分支：`feat/android-webview-client`
- 适用版本：DeepTutor 1.7.2 起

## 1. 背景

DeepTutor 的 Web 端在移动端浏览器中已做响应式适配，但浏览器自身的地址栏、工具栏与手势会破坏沉浸感。目标是提供一个 Android 客户端，启动即全屏加载用户自己部署的 DeepTutor Web 端，获得接近原生的使用体验。

该客户端**不运行 DeepTutor 服务**，也不包含任何业务逻辑；它只负责承载 Web 页面并补齐浏览器缺失的少量原生能力。

## 2. 目标与非目标

### 目标

1. 启动即进入用户配置的 DeepTutor 地址，无浏览器 UI，保留系统状态栏。
2. 补齐浏览器在 App 场景下缺失的能力：文件上传（含**从相册直接选择照片**）、文件下载（含 `blob:`）、外部链接跳转、返回键、下拉刷新、加载失败重试。
3. 一个不遮挡正文的原生设置入口，可修改服务器地址、清除站点数据、重新加载。
4. 随 DeepTutor 版本通过 GitHub Actions 构建并发布 APK。
5. 顺带给 Web 端补充 PWA manifest，使手机浏览器也可"添加到主屏幕"。

### 非目标

- 不重写移动端界面，不做原生聊天/学习页面。
- 不内置 DeepTutor 运行时，不本地启动 Python/Node 服务。
- 不做后台保活、推送通知、回复完成提醒。
- 不修复 WebView 不支持或支持不稳定的 Web API（见 §12 已知限制）。
- 不提供 iOS 客户端。
- 本轮不集成 MathLive 数学输入法（见 §13 后续事项）。

## 3. 已确认的现状与约束

| 项 | 结论 | 影响 |
| --- | --- | --- |
| Web 框架 | Next.js 16，App Router | 客户端只需加载根地址 |
| API / WebSocket | 全部同源，经 Next.js 代理转发 | 客户端无需单独配置 API 端口或 WS 地址 |
| 登录 | 已有 `/login` 页面与 Cookie 会话 | WebView 共享 Cookie，重启免登录 |
| TLS | `*.raaa.fun`，Let's Encrypt，有效期至 2026-12-23，TLS 1.2 | 无需任何证书绕过或自定义证书固定 |
| PWA | 无 manifest、无 Service Worker | 需新增 manifest；不引入 SW |
| 下载 | 存在大量 `URL.createObjectURL` + `a[download]`，以及 `window.open('blob:…')` | 原生 `DownloadListener` 抓不到，必须用注入脚本 + 原生桥 |
| 附件上传 | 聊天输入框仅有一个隐藏 `<input type="file" multiple>`，`accept` 混合图片与文档，无 `capture` 属性，也无图片专用入口 | Android 只会弹文件选择器，无法直接进入相册；壳层需按 `accept` 分流并提供相册入口，**不改 Web 端** |
| 主题 | 默认跟随 `prefers-color-scheme`，可手动切换并存于 `localStorage` | 需监听 `html.dark` 同步系统栏图标 |
| 视口 | `layout.tsx` 只设置 `interactiveWidget: "resizes-content"`，无 `viewport-fit=cover` | 不做 edge-to-edge 内容延伸，改用系统栏 insets |
| 构建机 | 本机有 JDK 21 与 platform-tools（`C:\ADB`），无 Android SDK / Gradle / Android Studio | 构建与发布交给 CI；本机仅做安装与真机验证 |

## 4. 技术选型

选择 **Kotlin + 原生 WebView 单 Activity**。

- 与 Capacitor 相比：不引入额外框架和约 6–10 MB 运行时，壳层行为完全可控。
- 与 TWA 相比：不依赖 Chrome 内核与 Digital Asset Links，下载、上传、下拉刷新等不受 TWA 限制。

依赖仅包含 AndroidX 基础库与 `androidx.webkit`（用于 `addDocumentStartJavaScript`、`WebViewFeature` 能力检测）。

## 5. 架构

### 5.1 组件

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `MainActivity` | `MainActivity.kt` | 承载 WebView、连接页、设置面板、悬浮按钮；处理返回键与系统栏 |
| `ShellWebViewClient` | `ShellWebViewClient.kt` | 页面加载、外链拦截、错误回调、URL 变更通知 |
| `ShellChromeClient` | `ShellChromeClient.kt` | 文件选择器、`window.open`、进度 |
| `UploadPicker` | `UploadPicker.kt` | 按 `accept` 分流，弹出「拍照 / 相册 / 文件」菜单 |
| `DownloadBridge` | `DownloadBridge.kt` | `blob:` 分块接收、文件落盘、`http(s)` 下载转交 DownloadManager |
| `ThemeBridge` | `ThemeBridge.kt` | 接收主题与滚动状态回调，调整系统栏与下拉刷新开关 |
| `ServerStore` | `ServerStore.kt` | SharedPreferences 读写服务器地址、允许 HTTP 开关 |
| `ConnectScreen` | `ConnectScreen.kt` | 首次启动的地址输入与连通性校验 |
| `SettingsSheet` | `SettingsSheet.kt` | 修改地址、清除站点数据、重新加载、关于 |
| `LoadErrorView` | `LoadErrorView.kt` | 加载失败/断网错误页 |
| 注入脚本 | `assets/shell.js` | 主题监听、滚动位置上报、`blob` 下载拦截 |

### 5.2 数据流

```
启动
 ├─ 读取 SharedPreferences.serverUrl
 │   ├─ 为空 → ConnectScreen → 校验 → 保存 → 加载
 │   └─ 有值 → 直接加载
 ├─ WebView 加载 → 页面自行完成登录、API、WebSocket
 └─ 壳层仅在以下事件介入：
     文件选择 / 下载 / 外链 / 返回键 / 下拉刷新 / 主题变化 / 加载失败
```

## 6. 功能规格

### 6.1 首次启动与服务器地址

- 首次启动（或地址被清除）显示 `ConnectScreen`：
  - 输入框提示 `https://your-domain:4433`
  - 自动补全缺失的 `https://`
  - 校验：必须是 `http` 或 `https`，主机与端口可解析
  - "连接"时先做一次轻量预检（HTTP HEAD/GET 根路径，超时 8 秒）；失败给出可读原因，但允许"仍然继续"
  - HTTP 地址显示"不安全连接"警告，但仍允许（覆盖内网部署场景）
- 地址来源优先级：用户输入 > `BuildConfig.DEFAULT_SERVER_URL` > 空。
  - `DEFAULT_SERVER_URL` 由 Gradle 属性 `deeptutorDefaultServerUrl` 注入，CI 读取仓库变量 `DEEPTUTOR_ANDROID_DEFAULT_URL`，未配置时为空字符串。
- 地址保存后，后续启动直接加载，不再显示连接页。
- 登出由 Web 端自行处理并跳转 `/login`，壳层不介入。需要更换服务器时，从设置面板修改。

### 6.2 加载与全屏

- 保留系统状态栏与导航栏，隐藏浏览器地址栏/工具栏；`setDecorFitsSystemWindows(true)`，WebView 填充系统栏以外的区域。
- 系统栏颜色：浅色主题为白色背景 + 深色图标；深色主题为深色背景 + 浅色图标。由 `ThemeBridge` 根据页面 `html.dark` 状态设置。
- `WebView` 背景色与系统栏一致，避免加载瞬间白闪。
- 启动页使用 AndroidX SplashScreen：品牌色背景 + `web/public/brand/color-logo.png`，加载首帧后退出。
- 支持系统深色模式；WebView 的 `prefers-color-scheme` 跟随系统。

### 6.3 返回键

- `webView.canGoBack()` 为真 → `goBack()`。
- 已在首个页面 → 双击返回退出：首次提示"再按一次退出"，2 秒内再次按下才退出。
- 连接页与设置面板打开时，返回键优先关闭它们。

### 6.4 悬浮设置按钮

- 半透明圆形按钮，直径 40dp，可拖动到屏幕任意边缘。
- 停止操作 2 秒后自动贴边并降低不透明度至 30%；点击时恢复。
- 位置保存到 SharedPreferences，下次启动恢复。
- 首次启动在连接页提示"可通过悬浮按钮修改服务器地址"。
- 设置面板打开时按钮隐藏。

### 6.5 文件上传（含相册）

实现 `WebChromeClient.onShowFileChooser`，读取 `FileChooserParams` 后分三条路径，**不改动 Web 端**：

1. `isCaptureEnabled == true` → 系统相机（`Intent.ACTION_IMAGE_CAPTURE` + `FileProvider` 临时文件）。
2. `acceptTypes` 全部为图片（`image/*`、`image/jpeg` 等）→ 直接打开系统相册：
   - Android 13+：`MediaStore.ACTION_PICK_IMAGES`，`EXTRA_PICK_IMAGES_MAX = 10`，支持多选。
   - Android 8–12：`ACTION_GET_CONTENT` + `type="image/*"` + `EXTRA_ALLOW_MULTIPLE`。
3. 其他情况（含聊天附件这种图片与文档混合的 `accept`）→ 弹出原生底部菜单，三选一：
   - 拍照 → 系统相机
   - 从相册选择 → 第 2 条的相册 Intent
   - 选择文件 → `ACTION_OPEN_DOCUMENT`（`CATEGORY_OPENABLE`，允许多选）

约束：

- 不声明 `CAMERA` 权限，由系统相机应用完成拍摄。
- `filePathCallback` 必须且只能回调一次；用户取消时传空数组，避免网页输入框卡死。
- 目标 Intent 不可用时回落到 `ACTION_OPEN_DOCUMENT`。
- 该方案覆盖所有上传入口（聊天、测验图片、笔记等），不依赖任何 Web 端改动。

### 6.6 下载

两条路径，统一落到公共 Downloads 目录：

1. **`http(s)` 直链**：`DownloadListener` → `DownloadManager`，通知栏展示进度。
   - Android 10+ 使用系统默认公共目录，无需权限。
   - Android 8–9 声明 `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="28"`）并在首次下载前请求。
2. **`blob:` 下载**：注入脚本拦截，经原生桥落盘。
   - 注入脚本在 `document-start` 阶段包装 `URL.createObjectURL` 记录 `blob → Blob` 映射；捕获带 `download` 属性的 `<a>` 点击；拦截 `window.open('blob:…')`。
   - 通过 `fetch(blobUrl).arrayBuffer()` 读取，按 512 KB 分块转 Base64，调用桥方法 `beginBlob(name, mime)` / `appendBlob(index, base64)` / `finishBlob()`。
   - 原生合并写入：Android 10+ 用 `MediaStore.Downloads`，Android 8–9 用公共 Downloads 目录。
   - 单文件上限 32 MB，超限时 Toast 提示失败。
   - 文件名清洗：去除路径分隔符与控制字符，重名自动追加序号。

### 6.7 外部链接与 `window.open`

- 主框架内导航：与**当前配置的服务器 host 相同**的地址留在 WebView；其他 `http(s)` 地址交给系统浏览器。外链判定必须基于运行时的服务器地址，不得硬编码域名（该 APK 会对其他自部署用户发布）。
- `setSupportMultipleWindows(true)` + `onCreateWindow`：
  - `http(s)` 外链 → 系统浏览器打开，返回 `false`。
  - `blob:` → 走 §6.6 保存流程。
  - 其他协议（`mailto:`、`tel:`、`intent:`）→ 交给系统处理。
- 已知限制：依赖 `window.open` 返回窗口对象的 OAuth 流程（如 Codex 登录）在手机上不保证可用，见 §12。

### 6.8 下拉刷新

- `SwipeRefreshLayout` 包裹 WebView，触发距离 120dp。
- 是否可用由注入脚本上报的"页面是否在顶部"决定：
  - 脚本在 `touchstart` 时用 `document.elementFromPoint` 找到可滚动的祖先，计算 `scrollTop`，并通过 `onScrollStateChanged(atTop)` 上报。
  - `SwipeRefreshLayout.setOnChildScrollUpCallback` 返回 `!atTop`，避免聊天内容滚动时误触发。
- 触发后执行 `reload()`。
- 已知限制：流式生成过程中下拉刷新会中断当前回答（见 §12）。

### 6.9 加载失败与断网

- `onReceivedError`（主框架）与 `onReceivedHttpError`（主框架 4xx/5xx）→ 显示 `LoadErrorView`：
  - 品牌图、错误摘要（超时 / DNS 失败 / 证书错误 / HTTP 状态码）
  - "重试"按钮
  - "服务器设置"按钮
- 证书错误：直接显示错误，**不做任何绕过**。
- 页面加载中显示细进度条（顶部 2dp），不遮挡内容。

### 6.10 主题联动

- 注入脚本用 `MutationObserver` 监听 `document.documentElement` 的 `class` 变化，调用 `onThemeChanged(isDark)`。
- 原生据此切换系统栏颜色与图标明暗。
- 桥对象只暴露这一个无副作用方法，不接受任意路径或命令。

### 6.11 设置面板

从底部滑出的原生面板：

| 项 | 行为 |
| --- | --- |
| 服务器地址 | 显示当前地址，可编辑；保存后重新加载 |
| 清除站点数据 | 清除 WebView Cookie、localStorage、缓存，并回到连接页 |
| 重新加载 | `reload()` |
| 关于 | App 版本、DeepTutor 版本、当前地址 |

不提供"退出登录"按钮：登出属于 Web 端功能，避免与 Web 会话状态产生两套事实。

### 6.12 生命周期

- 切后台不主动断开 WebView；WebSocket 重连由 Web 端既有逻辑负责。
- `onPause`/`onResume` 调用 `webView.onPause()` / `onResume()`，避免后台占用音频。
- 配置变更（旋转、深浅色切换）不重建 Activity，`android:configChanges` 声明 `orientation|screenSize|uiMode|smallestScreenSize`。

## 7. 数据存储

`SharedPreferences`（文件名 `shell_prefs`）：

| Key | 类型 | 说明 |
| --- | --- | --- |
| `server_url` | String | 当前服务器地址 |
| `allow_http` | Boolean | 是否允许明文 HTTP（默认 false） |
| `fab_x` / `fab_y` | Int | 悬浮按钮位置 |
| `connect_hint_shown` | Boolean | 首次提示是否已展示 |

不存储任何凭据，登录态完全由 WebView Cookie 管理。

## 8. 安全

- 只接受 `http` / `https`；默认禁止明文 HTTP，需在连接页显式确认。
- `mixedContentMode = NEVER_ALLOW`；`allowFileAccess = false`；`allowContentAccess = false`。
- 不实现 `shouldOverrideUrlLoading` 之外的 URL 重写，不注入自定义 UA 之外的身份标识（UA 追加 `DeepTutorAndroid/<version>`，便于 Web 端未来识别）。
- JavaScript 桥仅暴露 `beginBlob`、`appendBlob`、`finishBlob`、`onThemeChanged`、`onScrollStateChanged`；全部为固定签名、无反射、无动态命令执行。
- 桥方法写文件时强制写入 Downloads 目录并清洗文件名，防止路径穿越。
- 不信任服务器证书例外，不实现 `onReceivedSslError` 的 `proceed()`。

## 9. 构建与发布

### 9.1 工程

```
apps/android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/wrapper/…
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/fun/raaa/deeptutor/…
  app/src/main/assets/shell.js
  app/src/main/res/…
```

- 包名 `fun.raaa.deeptutor`，显示名 `DeepTutor`
- `minSdk 26`，`targetSdk 35`，`compileSdk 35`
- `versionName` = `deeptutor/__version__.py` 的值（当前 `1.7.2`）
- `versionCode` = `major * 10000 + minor * 100 + patch`（`1.7.2` → `10702`）
- 图标：以 `web/public/brand/color-logo.png` 生成自适应图标（前景 + 品牌色背景），并提供 512×512 的 Play 商店备用资源

### 9.2 CI 工作流

新增 `.github/workflows/android-release.yml`：

- 触发：`release: types: [published]`；`workflow_dispatch`（仅产出 artifact，不发布）；PR 或推送到 `dev` 且改动 `apps/android/**` 时构建 debug 作为 artifact。
- 版本校验：Release tag 必须等于 `v<version>`（`version` 取自 `deeptutor/__version__.py`），否则失败。
- 步骤：checkout → setup-java 21 (temurin) → setup-android → Gradle 缓存 → 注入版本 → 签名构建 → 上传 Release 资源。
- 签名 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
- **Secret 缺失时降级为 `assembleDebug`**，产物命名为 `DeepTutor-Android-v<version>-debug.apk`，并在 workflow 日志中明确标注，保证流程可验证而不静默失败。
- 正式产物命名：`DeepTutor-Android-v<version>.apk`。
- 密钥生成命令写入 `apps/android/README.md`（`keytool -genkeypair …`），并说明私钥与密码不得入库。

## 10. PWA manifest（Web 端）

- 新增 `web/app/manifest.ts`，导出 Next.js `MetadataRoute.Manifest`：
  - `name: "DeepTutor"`，`short_name: "DeepTutor"`
  - `start_url: "/"`，`display: "standalone"`
  - `background_color` / `theme_color` 取现有品牌色
  - `icons`：192、512、512 maskable，复用 `web/public/brand/` 资源
- 在 `web/app/layout.tsx` 的 `metadata` 中声明 `manifest: "/manifest.webmanifest"`。
- **不引入 Service Worker**，避免缓存导致版本更新不生效。

## 11. 测试与验收

### 11.1 CI

- `assembleDebug` 与（有签名时）`assembleRelease` 编译通过。
- WebView 行为不做自动化测试。

### 11.2 真机手工验收清单

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 首次安装启动 | 出现连接页，输入地址后加载成功 |
| 2 | 再次启动 | 直接进入上次地址，免登录 |
| 3 | 系统栏 | 无浏览器地址栏；深浅色下状态栏图标与背景正确 |
| 4 | 返回键 | 逐级后退；首页双击退出 |
| 5 | 混合类型附件入口（聊天「资源/附件」） | 弹出「拍照 / 从相册选择 / 选择文件」三选一，三者均可用 |
| 5b | 纯图片上传入口（测验图片等） | 直接进入系统相册，可多选 |
| 5c | 取消选择 | 网页输入框回到可用状态，不卡死 |
| 6 | `http(s)` 下载 | 落入 Downloads，通知栏可见 |
| 7 | `blob:` 下载（笔记导出/闪卡/练习模板） | 文件成功落盘且可打开 |
| 8 | 外部链接 | 跳转系统浏览器，App 内不导航 |
| 9 | 下拉刷新 | 仅在页面顶部触发；聊天中滚动不误触 |
| 10 | 断网启动 | 显示错误页，恢复网络后可重试 |
| 11 | 悬浮按钮 | 可拖动、自动贴边；打开设置可改地址并生效 |
| 12 | 清除站点数据 | Cookie 与缓存清空，回到连接页 |
| 13 | 旋转屏幕 | 不重建页面，聊天内容不丢失 |

## 12. 已知限制

- **Web Notification 不可用**：WebView 不支持 Web Notification API，日历提醒中的 `new Notification(...)` 会静默失败（页面已有能力判断）。
- **`showDirectoryPicker` 不可用**：聊天记录目录导入功能在 App 内不可用（页面已降级）。
- **`speechSynthesis` 支持不稳定**：阅读页"朗读"在部分设备上无声或不可用。
- **OAuth 弹窗流程**：依赖 `window.open` 句柄的登录（如 Codex）在系统浏览器接管后可能中断。
- **流式生成中下拉刷新**：会中断当前回答。
- **状态栏非沉浸**：内容不延伸到状态栏下方；如需真·edge-to-edge，需 Web 端补 `viewport-fit=cover` 与顶部 safe-area 适配。

## 13. 后续事项

### 13.1 MathLive 数学输入（Web 端独立子项目）

**参考物料定位**

| 路径 | 实际内容 | 是否采用 |
| --- | --- | --- |
| `D:\VC\mathlive` | MathLive 官方源码仓库，版本 0.110.0，MIT 许可，含 `dist/`（`mathlive.mjs` 约 1.4 MB 未压缩）与 KaTeX 字体 | 仅作 API 与资源参考，**不作为构建依赖路径**（本地 `file:` 依赖不可复现） |
| `D:\VC\mathlive_editor_package` | 第三方 Flutter 包 `mathlive_studio` 0.1.2，用 `webview_flutter` / iframe 承载 MathLive | 不采用；仅借鉴其 `\(...\)` 行内公式约定与「内联编辑器 / 全屏编辑器」两种形态 |

**Web 端现状**

- 输入侧：单个 `textarea`，IME 组合由 `useImeComposing` 管理；命令式接口仅有 `clear` / `getValue` / `setValue`（[ComposerInput.tsx](file:///e:/VC/DeepTutor/web/components/chat/home/ComposerInput.tsx#L253-L277)）。
- 渲染侧：`MarkdownRenderer` 默认 `enableMath = enableMath ?? hasMarkdownMath(content)`，`$...$` / `$$...$$` 经 `remark-math` + `rehype-katex` 渲染，**已就绪**，无需改动。
- 依赖侧：项目当前**没有** `mathlive` / `math-field`。

**方案对比**

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A. 公式按钮 + 底部 MathLive 面板 | 工具栏加 Σ 按钮，弹出 `<math-field>` 面板，确认后把 LaTeX 以 `$...$` 插入光标处 | **推荐**：对现有 textarea 零侵入，移动端可与系统输入法互斥切换 |
| B. 轻量符号工具条 | 一排分数/根号/上下标/积分按钮，点击插入 LaTeX 模板 | 体积最小，可作为 A 的快捷补充，但不具备自由编辑能力 |
| C. `math-field` 直接替换 textarea | 富文本混合编辑 | 不做：需自行维护文本/公式分段、光标、IME、粘贴与草稿兼容，改动远超收益 |

**方案 A 的实施要点**

1. 依赖：新增 `mathlive`（锁定 0.110.x），**仅动态导入**；不使用本地 `file:` 路径。
2. 资源自托管：新增 `web/scripts/copy-mathlive-assets.mjs`，把 `node_modules/mathlive/dist/` 的字体与音效复制到 `public/mathlive/`，并设置 `MathfieldElement.fontsDirectory` / `soundsDirectory`；不依赖 CDN（自部署与隐私要求）。构建前钩子参照现有 `copy-pdfjs-assets.mjs`。
3. 组件：新增 `web/components/chat/home/MathInputPanel.tsx`（Client Component，`dynamic(..., { ssr: false })`），内含 `<math-field>` 与 MathLive 虚拟键盘。
4. 焦点与输入法切换：打开面板时让 `textarea` 失焦（系统输入法收起），面板内可切换「数学键盘 / 系统键盘」；关闭面板后把焦点还给 `textarea`，系统输入法恢复。
5. 插入：`ComposerInput` 新增 `insertAtCursor(text)`（读取 `selectionStart` / `selectionEnd`，拼接后调用 `setInputBoth`，恢复光标并聚焦）；`ChatComposer` 用 `$...$` 包裹 `mathfield.getValue('latex')` 后调用。
6. 入口与文案：工具栏 `styles.context` 区域新增 Σ 按钮，文案走 `web/locales`。
7. 主题：MathLive CSS 变量映射到现有 `--foreground` / `--background` / `--primary`，随 `html.dark` 切换。
8. 体积：`mathlive` 必须惰性加载；合入前跑 `npm run perf:check` 复核路由预算。
9. 测试：Vitest 覆盖 `insertAtCursor` 的拼接与光标行为（不渲染 `math-field`）；真机验收覆盖 WebView 与手机浏览器。

**风险与注意事项**

- IME 组合状态未结束时插入文本会产生异常字符，插入前需确保 `compositionend` 已触发。
- `@` 提及与 `/` 命令依赖光标位置，插入后需同步刷新这两个弹出层的状态。
- Android WebView 中字体加载与虚拟键盘遮挡需实测；面板自身要处理底部 insets。
- 输出分隔符必须与渲染端一致（行内 `$...$`），否则公式不渲染。

**范围**：独立 PR，不纳入安卓壳首版；壳侧无需任何代码改动，仅需已验证的 WebView 环境（JS、字体、软键盘 `adjustResize`）。

### 13.2 真·edge-to-edge

Web 端增加 `viewportFit: "cover"` 并补齐顶部 safe-area 后，壳端去掉系统栏 insets。

### 13.3 回复完成通知

需要 Web 端通过桥上报流式结束事件，并在 Android 侧接入通知渠道。

### 13.4 iOS 客户端

不在本轮范围。

## 14. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 不同 ROM 的文件选择器/保存路径差异 | 全部走 SAF 与 MediaStore，不依赖厂商文件管理器路径 |
| 注入脚本被页面 CSP 或严格模式影响 | 使用 `androidx.webkit` 的 `addDocumentStartJavaScript`；不支持时退回 `onPageStarted` 注入并记录日志 |
| 大文件 `blob` 传输内存占用 | 512 KB 分块 + 32 MB 上限 |
| 悬浮按钮遮挡内容 | 半透明、可拖动、自动贴边隐藏 |
| 无签名 Secret 时无法产出正式包 | 明确降级为 debug 包并标注，不静默发布 |
| 本机无 Android SDK 导致无法本地调试 | 以 CI 产物 + `adb install` 验证；需要时临时安装 SDK |
