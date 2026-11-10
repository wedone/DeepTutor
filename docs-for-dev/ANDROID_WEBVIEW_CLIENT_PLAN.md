# DeepTutor Android WebView 客户端 实施计划

> **给执行者：** 逐步执行本计划。每个任务都以可验证的产物结束；涉及纯逻辑的步骤先写失败测试再实现。规格见 [ANDROID_WEBVIEW_CLIENT.md](ANDROID_WEBVIEW_CLIENT.md)。所有改动在分支 `feat/android-webview-client`，不要在 `main` 上提交。

**目标：** 交付一个 Android APK，启动后全屏加载用户自部署的 DeepTutor Web 端，并补齐上传（含相册）、下载（含 blob）、外链、返回键、下拉刷新、错误重试与原生设置。

**架构：** 单 Activity + 原生 WebView；壳层只处理浏览器缺失的能力，业务全部由 Web 页面承担；通过 `addDocumentStartJavaScript` 注入最小脚本，用 `@JavascriptInterface` 桥接主题、滚动位置与 blob 下载。

**技术栈：** Kotlin、AndroidX（core-ktx / appcompat / webkit / swiperefreshlayout / splashscreen / material）、Gradle Kotlin DSL、GitHub Actions。

**范围说明：** 本计划只覆盖安卓壳与 PWA manifest。MathLive 数学输入是 Web 端独立 PR，见规格 §13.1，不在本计划内。

---

## 文件结构

```
apps/android/
├── settings.gradle.kts          模块声明
├── build.gradle.kts             插件版本
├── gradle.properties            全局属性
├── gradle/wrapper/…             wrapper（由 Task 2 生成）
├── gradlew / gradlew.bat        由 Task 2 生成
├── gradle/libs.versions.toml    依赖版本集中管理
└── app/
    ├── build.gradle.kts         应用配置、版本注入、签名
    ├── proguard-rules.pro       发布混淆规则（保留桥接类）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/shell.js                注入脚本（主题/滚动/blob）
        │   ├── java/dev/raaa/deeptutor/
        │   │   ├── MainActivity.kt            壳主界面
        │   │   ├── ConnectActivity.kt         首次启动地址输入
        │   │   ├── ShellWebViewClient.kt      加载/外链/错误
        │   │   ├── ShellChromeClient.kt       文件选择/window.open/进度
        │   │   ├── UploadPicker.kt            相册/相机/文件分流
        │   │   ├── DownloadBridge.kt          下载落盘与 blob 合并
        │   │   ├── ShellBridge.kt             @JavascriptInterface 桥
        │   │   ├── ServerStore.kt             地址与偏好存储
        │   │   ├── ServerUrl.kt               地址规范化与校验
        │   │   ├── SettingsSheet.kt           原生设置面板
        │   │   ├── LoadErrorView.kt           错误页
        │   │   └── FloatingSettingsButton.kt  可拖动悬浮按钮
        │   └── res/                           布局、主题、图标、FileProvider
        └── test/java/dev/raaa/deeptutor/
            ├── ServerUrlTest.kt
            ├── FileNameTest.kt
            └── BlobAssemblerTest.kt
.github/workflows/android-release.yml
web/app/manifest.ts                        PWA manifest
```

---

## Task 1：准备 Android 构建工具链

**Files:** 无仓库文件改动（仅本机环境）。

- [ ] **Step 1: 下载并解压 Android commandline-tools**

```powershell
New-Item -ItemType Directory -Force C:\Android\Sdk\cmdline-tools | Out-Null
Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$env:TEMP\cmdline-tools.zip"
Expand-Archive "$env:TEMP\cmdline-tools.zip" -DestinationPath "$env:TEMP\cmdline-tools" -Force
Move-Item "$env:TEMP\cmdline-tools\cmdline-tools" 'C:\Android\Sdk\cmdline-tools\latest' -Force
```

- [ ] **Step 2: 安装平台与构建工具**

```powershell
$env:ANDROID_HOME='C:\Android\Sdk'
& 'C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat' --sdk_root=$env:ANDROID_HOME 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
```

Expected: 最后输出 `done`，无 `Warning: Failed to install`。

- [ ] **Step 3: 接受许可并写入用户环境变量**

```powershell
& 'C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat' --sdk_root=$env:ANDROID_HOME --licenses
[Environment]::SetEnvironmentVariable('ANDROID_HOME','C:\Android\Sdk','User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT','C:\Android\Sdk','User')
```

- [ ] **Step 4: 安装 Gradle 8.9**

```powershell
Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile "$env:TEMP\gradle.zip"
Expand-Archive "$env:TEMP\gradle.zip" -DestinationPath "$env:USERPROFILE\tools" -Force
[Environment]::SetEnvironmentVariable('Path', "$env:Path;$env:USERPROFILE\tools\gradle-8.9\bin", 'User')
```

- [ ] **Step 5: 验证**

```powershell
gradle -v
adb version
```

Expected: `Gradle 8.9`、`Android Debug Bridge version 1.0.41`。

---

## Task 2：工程骨架与版本注入

**Files:**
- Create: `apps/android/settings.gradle.kts`
- Create: `apps/android/build.gradle.kts`
- Create: `apps/android/gradle.properties`
- Create: `apps/android/gradle/libs.versions.toml`
- Create: `apps/android/app/build.gradle.kts`
- Create: `apps/android/app/proguard-rules.pro`
- Create: `apps/android/app/src/main/AndroidManifest.xml`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`
- Create: `apps/android/app/src/main/res/values/strings.xml`
- Create: `apps/android/app/src/main/res/values/themes.xml`

- [ ] **Step 1: 写 `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DeepTutorAndroid"
include(":app")
```

- [ ] **Step 2: 写 `build.gradle.kts` 与 `gradle.properties`**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 3: 写 `gradle/libs.versions.toml`**

```toml
[versions]
coreKtx = "1.15.0"
appcompat = "1.7.0"
material = "1.12.0"
webkit = "1.12.1"
swiperefresh = "1.1.0"
splashscreen = "1.0.1"
activity = "1.9.3"
lifecycle = "2.8.7"
junit = "4.13.2"

[libraries]
core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
material = { module = "com.google.android.material:material", version.ref = "material" }
webkit = { module = "androidx.webkit:webkit", version.ref = "webkit" }
swiperefresh = { module = "androidx.swiperefreshlayout:swiperefreshlayout", version.ref = "swiperefresh" }
splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "splashscreen" }
activity = { module = "androidx.activity:activity-ktx", version.ref = "activity" }
lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
junit = { module = "junit:junit", version.ref = "junit" }
```

- [ ] **Step 4: 写 `app/build.gradle.kts`（含版本注入与可选签名）**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号唯一来源：仓库根目录的 deeptutor/__version__.py
val deeptutorVersion: String = run {
    val file = rootProject.file("../../deeptutor/__version__.py")
    require(file.exists()) { "找不到 ${file.path}" }
    Regex("""__version__\s*=\s*"([^"]+)"""")
        .find(file.readText())
        ?.groupValues?.get(1)
        ?: error("无法从 ${file.path} 解析版本号")
}

// 1.7.2 -> 10702；保证 Android 要求的单调递增整数
val deeptutorVersionCode: Int = deeptutorVersion
    .split(".")
    .let { (it + listOf("0", "0", "0")).take(3) }
    .let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }

android {
    namespace = "dev.raaa.deeptutor"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.raaa.deeptutor"
        minSdk = 26
        targetSdk = 35
        versionCode = deeptutorVersionCode
        versionName = deeptutorVersion
        manifestPlaceholders["deeptutorVersion"] = deeptutorVersion
        // CI 注入默认地址；本地构建默认为空，强制首次启动手动输入
        buildConfigField(
            "String",
            "DEFAULT_SERVER_URL",
            "\"${project.findProperty("deeptutorDefaultServerUrl") ?: ""}\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank().not()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.webkit)
    implementation(libs.swiperefresh)
    implementation(libs.splashscreen)
    implementation(libs.activity)
    implementation(libs.lifecycle.runtime)
    testImplementation(libs.junit)
}
```

- [ ] **Step 5: 写 `proguard-rules.pro`**

```proguard
# JS 桥接方法不能被混淆或移除
-keepclassmembers class dev.raaa.deeptutor.ShellBridge {
    @android.webkit.JavascriptInterface <methods>;
}
```

- [ ] **Step 6: 写最小 `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <application
        android:name=".DeepTutorApp"
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.DeepTutor"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|uiMode"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> `usesCleartextTraffic="true"` 允许用户显式填写内网 HTTP 地址；HTTPS 页面内的混合内容仍被 WebView 的 `MIXED_CONTENT_NEVER_ALLOW` 阻止，连接页会对 HTTP 给出不安全提示。

同时创建 `apps/android/.gitignore`，避免构建产物与本地 SDK 路径入库：

```gitignore
.gradle/
build/
.kotlin/
local.properties
*.iml
captures/
```

- [ ] **Step 7: 写 `MainActivity.kt` 占位、`strings.xml`、`themes.xml`，并创建 `DeepTutorApp.kt`**

```kotlin
package dev.raaa.deeptutor

import android.app.Application

class DeepTutorApp : Application()
```

```kotlin
package dev.raaa.deeptutor

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "DeepTutor shell" })
    }
}
```

```xml
<resources>
    <string name="app_name">DeepTutor</string>
</resources>
```

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.DeepTutor" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
        <item name="android:windowBackground">@android:color/white</item>
    </style>
</resources>
```

- [ ] **Step 8: 生成 wrapper 并构建**

```powershell
gradle wrapper --gradle-version 8.9
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`，产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 9: 提交**

```bash
git add apps/android
git commit -m "feat(android): 新增 Android 壳工程骨架与版本注入"
```

---

## Task 3：服务器地址模型与校验（TDD）

**Files:**
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ServerUrl.kt`
- Create: `apps/android/app/src/test/java/dev/raaa/deeptutor/ServerUrlTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.raaa.deeptutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `补齐缺失的 https 前缀`() {
        assertEquals("https://eu.raaa.fun:4433", ServerUrl.normalize("eu.raaa.fun:4433"))
    }

    @Test
    fun `保留显式 http 前缀`() {
        assertEquals("http://192.168.1.10:3782", ServerUrl.normalize("http://192.168.1.10:3782"))
    }

    @Test
    fun `去掉末尾斜杠`() {
        assertEquals("https://eu.raaa.fun:4433", ServerUrl.normalize("https://eu.raaa.fun:4433/"))
    }

    @Test
    fun `拒绝空字符串`() {
        assertNull(ServerUrl.normalize("   "))
    }

    @Test
    fun `拒绝不支持的非 http 协议`() {
        assertNull(ServerUrl.normalize("ftp://example.com"))
    }

    @Test
    fun `识别明文 http`() {
        assertEquals(true, ServerUrl.isInsecure("http://10.0.0.2:3782"))
        assertEquals(false, ServerUrl.isInsecure("https://eu.raaa.fun:4433"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.raaa.deeptutor.ServerUrlTest'
```

Expected: 编译失败，提示 `Unresolved reference: ServerUrl`。

- [ ] **Step 3: 实现 `ServerUrl.kt`**

```kotlin
package dev.raaa.deeptutor

import java.net.URI

/** 服务器地址的规范化与校验。所有输入都经过这里，避免各处重复实现。 */
object ServerUrl {

    /** 返回规范化后的地址；非法输入返回 null。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 显式写了协议就必须是 http(s)，否则 "ftp://host" 会被当成 https 主机名
        val explicitScheme = SCHEME_PATTERN.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") return null
        val withScheme = if (explicitScheme == null) "https://$trimmed" else trimmed
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            buildString {
                append(scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
                val path = uri.path.orEmpty().trimEnd('/')
                if (path.isNotEmpty()) append(path)
            }
        } catch (_: Exception) {
            null
        }
    }

    private val SCHEME_PATTERN = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")

    /** 明文 HTTP 视为不安全连接，需要在界面上提示。 */
    fun isInsecure(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

    /** 判断一个导航目标是否与当前服务器同源（同 scheme + host + port）。 */
    fun isSameOrigin(serverUrl: String, target: String): Boolean = try {
        val a = URI(serverUrl)
        val b = URI(target)
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    } catch (_: Exception) {
        false
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.raaa.deeptutor.ServerUrlTest'
```

Expected: `BUILD SUCCESSFUL`，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add apps/android/app/src
git commit -m "feat(android): 新增服务器地址校验与同源判断"
```

---

## Task 4：地址存储与连接页

**Files:**
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ServerStore.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ConnectActivity.kt`
- Create: `apps/android/app/src/main/res/layout/activity_connect.xml`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 写 `ServerStore.kt`**

```kotlin
package dev.raaa.deeptutor

import android.content.Context

/** 壳层唯一的持久化入口，不保存任何凭据。 */
class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences("shell_prefs", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_SERVER_URL) else putString(KEY_SERVER_URL, value)
            }.apply()
        }

    var fabX: Int
        get() = prefs.getInt(KEY_FAB_X, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_FAB_X, value).apply()

    var fabY: Int
        get() = prefs.getInt(KEY_FAB_Y, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_FAB_Y, value).apply()

    var hintShown: Boolean
        get() = prefs.getBoolean(KEY_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_HINT_SHOWN, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_FAB_X = "fab_x"
        const val KEY_FAB_Y = "fab_y"
        const val KEY_HINT_SHOWN = "connect_hint_shown"
    }
}
```

- [ ] **Step 2: 写 `activity_connect.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="32dp">

        <ImageView
            android:layout_width="96dp"
            android:layout_height="96dp"
            android:contentDescription="@string/app_name"
            android:src="@mipmap/ic_launcher" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="@string/connect_title"
            android:textAppearance="?attr/textAppearanceHeadlineSmall" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/connect_subtitle"
            android:textAppearance="?attr/textAppearanceBodyMedium" />

        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/urlLayout"
            style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:hint="@string/connect_url_hint">

            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/urlInput"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:imeOptions="actionGo"
                android:inputType="textUri"
                android:maxLines="1" />
        </com.google.android.material.textfield.TextInputLayout>

        <TextView
            android:id="@+id/urlError"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:textColor="?attr/colorError"
            android:textSize="12sp"
            android:visibility="gone" />

        <Button
            android:id="@+id/connectButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="@string/connect_action" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 3: 写 `ConnectActivity.kt`**

```kotlin
package dev.raaa.deeptutor

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 首次启动时收集服务器地址；保存后进入壳界面。 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var store: ServerStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        setContentView(R.layout.activity_connect)

        val input = findViewById<TextInputEditText>(R.id.urlInput)
        val layout = findViewById<TextInputLayout>(R.id.urlLayout)
        val error = findViewById<View>(R.id.urlError)
        val button = findViewById<MaterialButton>(R.id.connectButton)

        if (input.text.isNullOrBlank()) {
            input.setText(BuildConfig.DEFAULT_SERVER_URL)
        }

        button.setOnClickListener {
            val normalized = ServerUrl.normalize(input.text?.toString().orEmpty())
            if (normalized == null) {
                layout.error = getString(R.string.connect_invalid)
                return@setOnClickListener
            }
            layout.error = null
            error.visibility = View.GONE
            if (ServerUrl.isInsecure(normalized)) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ConnectActivity)
                    .setTitle(R.string.connect_insecure_title)
                    .setMessage(R.string.connect_insecure_message)
                    .setPositiveButton(R.string.connect_continue) { _, _ -> saveAndOpen(normalized) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }
            saveAndOpen(normalized)
        }
    }

    private fun saveAndOpen(url: String) {
        val button = findViewById<MaterialButton>(R.id.connectButton)
        val error = findViewById<View>(R.id.urlError)
        button.isEnabled = false
        lifecycleScope.launch {
            val reachable = probe(url)
            button.isEnabled = true
            if (!reachable) {
                error.text = getString(R.string.connect_unreachable)
                error.visibility = View.VISIBLE
            }
            // 预检失败也允许继续，避免误判阻断内网/自签环境
            store.serverUrl = url
            startActivity(Intent(this@ConnectActivity, MainActivity::class.java))
            finish()
        }
    }

    private suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.responseCode in 200..499
        } catch (_: Exception) {
            false
        }
    }
}
```

- [ ] **Step 4: 补充文案与 Manifest 入口**

`strings.xml` 追加：

```xml
<string name="connect_title">连接到你的 DeepTutor</string>
<string name="connect_subtitle">输入你部署的 Web 端地址，例如 https://your-domain:4433</string>
<string name="connect_url_hint">服务器地址</string>
<string name="connect_action">连接</string>
<string name="connect_invalid">地址无效，请检查格式</string>
<string name="connect_unreachable">暂时无法连接，仍可继续尝试</string>
<string name="connect_insecure_title">不安全的连接</string>
<string name="connect_insecure_message">该地址使用明文 HTTP，内容可能在传输中被窃听。仅建议在可信内网使用。</string>
<string name="connect_continue">仍然继续</string>
```

`AndroidManifest.xml` 在 `MainActivity` 前加入：

```xml
<activity
    android:name=".ConnectActivity"
    android:exported="false"
    android:windowSoftInputMode="adjustResize" />
```

- [ ] **Step 5: 构建验证**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 提交**

```bash
git add apps/android
git commit -m "feat(android): 新增服务器地址存储与首次连接页"
```

---

## Task 5：WebView 主界面、全屏与主题联动

**Files:**
- Create: `apps/android/app/src/main/res/layout/activity_main.xml`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`
- Create: `apps/android/app/src/main/assets/shell.js`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ShellBridge.kt`

- [ ] **Step 1: 写 `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <ProgressBar
        android:id="@+id/progress"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="2dp"
        android:layout_gravity="top"
        android:indeterminate="false"
        android:max="100"
        android:visibility="gone" />
</FrameLayout>
```

- [ ] **Step 2: 写 `shell.js`**

```javascript
// DeepTutor Android 壳注入脚本：只做主题与滚动状态上报，不修改页面逻辑。
(function () {
  if (window.__deeptutorShellInstalled) return;
  window.__deeptutorShellInstalled = true;

  function reportTheme() {
    try {
      var dark = document.documentElement.classList.contains('dark');
      if (window.DeeptutorBridge) window.DeeptutorBridge.onThemeChanged(dark);
    } catch (e) {}
  }

  // 页面可能用内部容器滚动（document 的 scrollTop 恒为 0），
  // 因此从事件目标向上找最近的可滚动祖先，而不是只看 document。
  function nearestScrollable(node) {
    var el = node && node.nodeType === 1 ? node : null;
    while (el) {
      if (el.scrollHeight > el.clientHeight + 1) {
        var overflowY = getComputedStyle(el).overflowY;
        if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') return el;
      }
      el = el.parentElement;
    }
    return null;
  }

  function reportScroll(node) {
    try {
      if (!window.DeeptutorBridge) return;
      var el = nearestScrollable(node) || document.scrollingElement || document.documentElement;
      if (!el) return;
      window.DeeptutorBridge.onScrollStateChanged(el.scrollTop <= 1);
    } catch (e) {}
  }

  function attach() {
    var root = document.documentElement;
    if (!root) {
      // document-start 阶段 documentElement 可能尚未创建，稍后重试
      setTimeout(attach, 16);
      return;
    }
    new MutationObserver(reportTheme).observe(root, {
      attributes: true,
      attributeFilter: ['class'],
    });
    document.addEventListener('scroll', function (event) {
      reportScroll(event.target === document ? document.documentElement : event.target);
    }, { passive: true, capture: true });
    document.addEventListener('touchstart', function (event) {
      reportScroll(event.target);
    }, { passive: true });
    reportTheme();
    reportScroll(root);
  }

  attach();
})();
```

- [ ] **Step 3: 写 `ShellBridge.kt`**

```kotlin
package dev.raaa.deeptutor

import android.webkit.JavascriptInterface

/** 页面到原生的最小桥接面：只接受布尔值，无路径参数，无命令执行。 */
class ShellBridge(
    private val themeCallback: (Boolean) -> Unit,
    private val scrollCallback: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onThemeChanged(dark: Boolean) = themeCallback(dark)

    @JavascriptInterface
    fun onScrollStateChanged(atTop: Boolean) = scrollCallback(atTop)
}
```

> 构造参数不要与方法同名（Kotlin 会报 recursive type checking），因此用 `themeCallback` / `scrollCallback`。

- [ ] **Step 4: 在 `MainActivity.kt` 建立 WebView**

```kotlin
package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var store: ServerStore
    private var atPageTop = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ServerStore(this)
        val serverUrl = store.serverUrl
        if (serverUrl.isNullOrBlank()) {
            startActivity(android.content.Intent(this, ConnectActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)

        // targetSdk 35 在 Android 15+ 强制 edge-to-edge，这里显式声明并自行处理 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<FrameLayout>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString DeepTutorAndroid/${BuildConfig.VERSION_NAME}"
        }

        webView.addJavascriptInterface(
            ShellBridge(
                onThemeChanged = { dark -> runOnUiThread { applySystemBars(dark) } },
                onScrollStateChanged = { top -> atPageTop = top },
            ),
            "DeeptutorBridge",
        )

        installDocumentStartScript()
        applySystemBars(!isSystemDark())
        webView.loadUrl(serverUrl)
    }

    private var fallbackScript: String? = null

    private fun installDocumentStartScript() {
        val script = assets.open("shell.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        } else {
            // 旧内核：保存脚本，由 Task 9 的 ShellWebViewClient 在 onPageFinished 兜底注入
            fallbackScript = script
        }
    }

    /** 供 Task 9 创建 ShellWebViewClient 时使用。 */
    fun currentFallbackScript(): String? = fallbackScript

    private fun applySystemBars(dark: Boolean) {
        // API 35 下 statusBarColor 已失效，由根布局背景承担系统栏底色
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.decorView.setBackgroundColor(if (dark) Color.parseColor("#101114") else Color.WHITE)
    }

    private fun isSystemDark(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }
}
```

- [ ] **Step 5: 构建并真机验证**

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r apps\android\app\build\outputs\apk\debug\app-debug.apk
```

Expected: 打开后直接进入 Web 端首页；切换深色主题时状态栏图标随之变化；无浏览器地址栏。

- [ ] **Step 6: 提交**

```bash
git add apps/android
git commit -m "feat(android): 接入 WebView 主界面、沉浸式系统栏与主题桥"
```

---

## Task 6：返回键与下拉刷新

**Files:**
- Modify: `apps/android/app/src/main/res/layout/activity_main.xml`（用 `SwipeRefreshLayout` 包裹 WebView）
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`

- [ ] **Step 1: 用 `SwipeRefreshLayout` 包裹 WebView**

```xml
<androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    android:id="@+id/refresh"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
```

- [ ] **Step 2: 在 `MainActivity` 接入下拉刷新与返回键**

```kotlin
import androidx.activity.addCallback
```

```kotlin
private lateinit var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
private var lastBackAt = 0L

// onCreate 中，findViewById 之后
refresh = findViewById(R.id.refresh)
refresh.setOnRefreshListener {
    webView.reload()
    refresh.isRefreshing = false
}
refresh.setOnChildScrollUpCallback { _, _ -> !atPageTop }

onBackPressedDispatcher.addCallback(this) {
    if (webView.canGoBack()) {
        webView.goBack()
        return@addCallback
    }
    val now = System.currentTimeMillis()
    if (now - lastBackAt < 2_000) {
        finish()
        return@addCallback
    }
    lastBackAt = now
    android.widget.Toast.makeText(this@MainActivity, R.string.back_to_exit, android.widget.Toast.LENGTH_SHORT).show()
}
```

`strings.xml` 追加：

```xml
<string name="back_to_exit">再按一次退出</string>
```

- [ ] **Step 3: 验证**

```powershell
.\gradlew.bat :app:assembleDebug
```

真机检查：聊天列表向下滚动时下拉不应触发刷新；回退到首页后双击返回才退出。

- [ ] **Step 4: 提交**

```bash
git add apps/android
git commit -m "feat(android): 支持返回键层级与顶部下拉刷新"
```

---

## Task 7：文件上传（相册 / 相机 / 文件）

**Files:**
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/UploadPicker.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ShellChromeClient.kt`
- Create: `apps/android/app/src/main/res/xml/file_paths.xml`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`

- [ ] **Step 1: 写 `file_paths.xml` 与 FileProvider**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="captures" path="captures/" />
</paths>
```

`AndroidManifest.xml` 的 `<application>` 内追加：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 2: 写 `UploadPicker.kt`**

```kotlin
package dev.raaa.deeptutor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

/**
 * 文件选择分流：
 * 1. capture=true 直接开相机；
 * 2. accept 全是图片时直接进相册；
 * 3. 其余情况弹出「拍照 / 从相册选择 / 选择文件」。
 *
 * 必须在 Activity.onCreate 中构造，保证 registerForActivityResult
 * 发生在 STARTED 之前，否则会抛 IllegalStateException。
 */
class UploadPicker(private val activity: AppCompatActivity) {

    private var onResult: ((List<Uri>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var pendingCameraUri: Uri? = null
    private var suppressCancel = false

    private val mediaLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == Activity.RESULT_OK) deliver(result) else cancel() }

    private val documentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == Activity.RESULT_OK) deliver(result) else cancel() }

    private val cameraLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (result.resultCode == Activity.RESULT_OK && uri != null) deliverUris(listOf(uri)) else cancel()
    }

    fun show(
        acceptTypes: Array<String>?,
        captureEnabled: Boolean,
        allowMultiple: Boolean,
        onResult: (List<Uri>) -> Unit,
        onCancel: () -> Unit,
    ) {
        this.onResult = onResult
        this.onCancel = onCancel
        when {
            captureEnabled -> openCamera()
            isImageOnly(acceptTypes) -> openGallery(allowMultiple)
            else -> showChooser()
        }
    }

    private fun deliver(result: androidx.activity.result.ActivityResult) {
        val action = result.data
        val uris = mutableListOf<Uri>()
        action?.data?.let(uris::add)
        action?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        deliverUris(uris)
    }

    private fun deliverUris(uris: List<Uri>) {
        val onSuccess = onResult
        val onAbort = onCancel
        reset()
        if (uris.isEmpty()) onAbort?.invoke() else onSuccess?.invoke(uris)
    }

    private fun cancel() {
        val callback = onCancel
        reset()
        callback?.invoke()
    }

    private fun reset() {
        onResult = null
        onCancel = null
        suppressCancel = false
    }

    private fun isImageOnly(acceptTypes: Array<String>?): Boolean {
        val types = acceptTypes?.takeIf { it.isNotEmpty() } ?: return false
        return types.all { it == "image/*" || it.startsWith("image/") }
    }

    private fun openGallery(multiple: Boolean) {
        val picker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
            }
        } else {
            null
        }
        val intent = if (picker != null && picker.resolveActivity(activity.packageManager) != null) {
            picker
        } else {
            // 没有系统照片选择器时退回通用图片选择
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }
        launch(mediaLauncher, intent)
    }

    private fun openCamera() {
        val dir = File(activity.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file
        )
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            cancel()
            return
        }
        pendingCameraUri = uri
        launch(cameraLauncher, intent)
    }

    private fun openDocuments(multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        launch(documentLauncher, intent)
    }

    private fun launch(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
        intent: Intent,
    ) {
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
            cancel()
        }
    }

    private fun showChooser() {
        suppressCancel = false
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_upload_source, null)
        view.findViewById<android.view.View>(R.id.sourceCamera).setOnClickListener {
            suppressCancel = true
            sheet.dismiss()
            openCamera()
        }
        view.findViewById<android.view.View>(R.id.sourceGallery).setOnClickListener {
            suppressCancel = true
            sheet.dismiss()
            openGallery(multiple = true)
        }
        view.findViewById<android.view.View>(R.id.sourceFile).setOnClickListener {
            suppressCancel = true
            sheet.dismiss()
            openDocuments(multiple = true)
        }
        sheet.setOnDismissListener {
            if (!suppressCancel) cancel()
        }
        sheet.setContentView(view)
        sheet.show()
    }
}
```

- [ ] **Step 3: 写 `sheet_upload_source.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/upload_source_title"
        android:textAppearance="?attr/textAppearanceTitleMedium" />

    <Button
        android:id="@+id/sourceCamera"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/upload_source_camera" />

    <Button
        android:id="@+id/sourceGallery"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/upload_source_gallery" />

    <Button
        android:id="@+id/sourceFile"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/upload_source_file" />
</LinearLayout>
```

- [ ] **Step 4: 写 `ShellChromeClient.kt` 并在 `MainActivity` 注册**

```kotlin
package dev.raaa.deeptutor

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

open class ShellChromeClient(
    private val uploadPicker: UploadPicker,
    private val onProgress: (Int) -> Unit,
) : WebChromeClient() {

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = filePathCallback
        uploadPicker.show(
            acceptTypes = fileChooserParams?.acceptTypes,
            captureEnabled = fileChooserParams?.isCaptureEnabled == true,
            allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
            onResult = { uris ->
                fileCallback?.onReceiveValue(uris.toTypedArray())
                fileCallback = null
            },
            onCancel = {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
            },
        )
        return true
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
}
```

`MainActivity` 中新增字段并在 `onCreate` 里**先**初始化（必须在 `setContentView` 之前，保证 `registerForActivityResult` 早于 STARTED）：

```kotlin
private lateinit var uploadPicker: UploadPicker

// onCreate 最前面，super.onCreate 之后立即执行
uploadPicker = UploadPicker(this)
```

`MainActivity.onCreate` 中追加：

```kotlin
webView.webChromeClient = ShellChromeClient(uploadPicker) { value ->
    progress.progress = value
    progress.visibility = if (value in 1..99) View.VISIBLE else View.GONE
}
```

`strings.xml` 追加：

```xml
<string name="upload_source_title">添加内容</string>
<string name="upload_source_camera">拍照</string>
<string name="upload_source_gallery">从相册选择</string>
<string name="upload_source_file">选择文件</string>
```

- [ ] **Step 5: 验证**

```powershell
.\gradlew.bat :app:assembleDebug
```

真机检查：聊天「附件」弹出三项；相册可多选；测试页面的纯图片入口直接进相册；取消后输入框可再次点击。

- [ ] **Step 6: 提交**

```bash
git add apps/android
git commit -m "feat(android): 上传按 accept 分流并支持相册/相机/文件"
```

---

## Task 8：下载（http(s) 与 blob）

**Files:**
- Create: `apps/android/app/src/test/java/dev/raaa/deeptutor/FileNameTest.kt`
- Create: `apps/android/app/src/test/java/dev/raaa/deeptutor/BlobAssemblerTest.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/FileName.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/BlobAssembler.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/DownloadBridge.kt`
- Modify: `apps/android/app/src/main/assets/shell.js`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/ShellBridge.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.raaa.deeptutor

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameTest {
    @Test
    fun `移除路径分隔符`() {
        assertEquals("etcpasswd.txt", FileName.sanitize("../../etc/passwd.txt"))
    }

    @Test
    fun `保留中文与扩展名`() {
        assertEquals("错题本.pdf", FileName.sanitize("错题本.pdf"))
    }

    @Test
    fun `空名回落为 download`() {
        assertEquals("download", FileName.sanitize("   "))
    }
}
```

```kotlin
package dev.raaa.deeptutor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobAssemblerTest {
    @Test
    fun `按序拼接分块`() {
        val assembler = BlobAssembler(maxBytes = 1024)
        assertTrue(assembler.append(0, byteArrayOf(1, 2)))
        assertTrue(assembler.append(1, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), assembler.finish())
    }

    @Test
    fun `超出上限时拒绝`() {
        val assembler = BlobAssembler(maxBytes = 3)
        assertTrue(assembler.append(0, byteArrayOf(1, 2)))
        assertFalse(assembler.append(1, byteArrayOf(3, 4)))
    }

    @Test
    fun `乱序分块返回失败`() {
        val assembler = BlobAssembler(maxBytes = 1024)
        assertFalse(assembler.append(1, byteArrayOf(1)))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.raaa.deeptutor.FileNameTest' --tests 'dev.raaa.deeptutor.BlobAssemblerTest'
```

Expected: 编译失败，提示 `Unresolved reference: FileName` / `BlobAssembler`。

- [ ] **Step 3: 实现 `FileName.kt` 与 `BlobAssembler.kt`**

```kotlin
package dev.raaa.deeptutor

object FileName {
    /** 去除路径分隔符与控制字符，避免写入 Downloads 时穿越目录。 */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .filter { !it.isISOControl() }
            .trim()
        return cleaned.ifEmpty { "download" }
    }
}
```

```kotlin
package dev.raaa.deeptutor

import java.io.ByteArrayOutputStream

/** 校验并合并页面分块传来的 blob 数据，强制顺序与大小上限。 */
class BlobAssembler(private val maxBytes: Int) {

    private val buffer = ByteArrayOutputStream()
    private var expectedIndex = 0

    @Synchronized
    fun append(index: Int, chunk: ByteArray): Boolean {
        if (index != expectedIndex) return false
        if (buffer.size() + chunk.size > maxBytes) return false
        buffer.write(chunk)
        expectedIndex += 1
        return true
    }

    @Synchronized
    fun finish(): ByteArray = buffer.toByteArray()
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.raaa.deeptutor.FileNameTest' --tests 'dev.raaa.deeptutor.BlobAssemblerTest'
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 扩展 `shell.js` 拦截 blob 下载**

在文件末尾追加：

```javascript
(function () {
  if (window.__deeptutorBlobHook) return;
  window.__deeptutorBlobHook = true;

  var MAX_BYTES = 32 * 1024 * 1024;
  var CHUNK = 512 * 1024;
  var blobs = {};
  var seq = 0;
  var originalCreate = URL.createObjectURL.bind(URL);

  URL.createObjectURL = function (blob) {
    var url = originalCreate(blob);
    try { blobs[url] = blob; } catch (e) {}
    return url;
  };

  async function saveBlob(url, name) {
    try {
      var blob = blobs[url] || (await fetch(url)).blob();
      if (blob.size > MAX_BYTES) throw new Error('too large');
      var buffer = await blob.arrayBuffer();
      var bytes = new Uint8Array(buffer);
      var id = 'blob-' + (++seq);
      if (!window.DeeptutorBridge.beginBlob(id, name, blob.type || '')) return;
      var index = 0;
      for (var offset = 0; offset < bytes.length; offset += CHUNK) {
        var slice = bytes.subarray(offset, Math.min(offset + CHUNK, bytes.length));
        var binary = '';
        for (var i = 0; i < slice.length; i++) binary += String.fromCharCode(slice[i]);
        if (!window.DeeptutorBridge.appendBlob(id, index++, btoa(binary))) return;
      }
      window.DeeptutorBridge.finishBlob(id);
    } catch (e) {
      if (window.DeeptutorBridge) window.DeeptutorBridge.onDownloadFailed(String(e));
    }
  }

  document.addEventListener('click', function (event) {
    var anchor = event.target && event.target.closest ? event.target.closest('a[download]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (href.startsWith('blob:')) {
      event.preventDefault();
      saveBlob(href, anchor.getAttribute('download') || 'download');
    }
  }, true);

  var originalOpen = window.open;
  window.open = function (url) {
    if (typeof url === 'string' && url.startsWith('blob:')) {
      saveBlob(url, 'download');
      return null;
    }
    return originalOpen.apply(window, arguments);
  };
})();
```

- [ ] **Step 6: 扩展 `ShellBridge.kt`**

```kotlin
class ShellBridge(
    private val themeCallback: (Boolean) -> Unit,
    private val scrollCallback: (Boolean) -> Unit,
    private val blobBegin: (String, String, String) -> Boolean,
    private val blobChunk: (String, Int, String) -> Boolean,
    private val blobFinish: (String) -> Unit,
    private val blobFailed: (String) -> Unit,
) {
    @JavascriptInterface
    fun onThemeChanged(dark: Boolean) = themeCallback(dark)

    @JavascriptInterface
    fun onScrollStateChanged(atTop: Boolean) = scrollCallback(atTop)

    @JavascriptInterface
    fun beginBlob(id: String, name: String, mime: String): Boolean = blobBegin(id, name, mime)

    @JavascriptInterface
    fun appendBlob(id: String, index: Int, base64: String): Boolean =
        blobChunk(id, index, base64)

    @JavascriptInterface
    fun finishBlob(id: String) = blobFinish(id)

    @JavascriptInterface
    fun onDownloadFailed(reason: String) = blobFailed(reason)
}
```

- [ ] **Step 7: 实现 `DownloadBridge.kt` 并在 `MainActivity` 接线**

```kotlin
package dev.raaa.deeptutor

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebView
import java.io.File

/** 下载统一入口：http(s) 交给系统 DownloadManager，blob 由分块桥接落盘。 */
class DownloadBridge(
    private val context: Context,
    private val toast: (String) -> Unit,
) : DownloadListener {

    private data class Pending(val name: String, val mime: String, val assembler: BlobAssembler)

    private val pending = mutableMapOf<String, Pending>()

    override fun onDownloadStart(url: String?, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        if (url == null) return
        if (url.startsWith("blob:")) return // 由注入脚本接管
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FileName.sanitize(name))
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    }

    fun begin(id: String, name: String, mime: String): Boolean {
        pending[id] = Pending(FileName.sanitize(name), mime, BlobAssembler(MAX_BYTES))
        return true
    }

    fun append(id: String, index: Int, base64: String): Boolean {
        val entry = pending[id] ?: return false
        val chunk = Base64.decode(base64, Base64.DEFAULT)
        return entry.assembler.append(index, chunk)
    }

    fun finish(id: String) {
        val entry = pending.remove(id) ?: return
        val bytes = entry.assembler.finish()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mime.ifBlank { "application/octet-stream" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = context.contentResolver.insert(collection, values)
                    ?: return toast("无法保存文件")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, entry.name).writeBytes(bytes)
            }
            toast("已保存到下载目录：${entry.name}")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    fun failed(reason: String) = toast("下载失败：$reason")

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
```

`MainActivity.onCreate` 中追加：

```kotlin
val downloads = DownloadBridge(this) { message ->
    runOnUiThread { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
}
webView.setDownloadListener(downloads)
webView.addJavascriptInterface(
    ShellBridge(
        onThemeChanged = { dark -> runOnUiThread { applySystemBars(dark) } },
        onScrollStateChanged = { top -> atPageTop = top },
        onBlobBegin = downloads::begin,
        onBlobChunk = downloads::append,
        onBlobFinish = downloads::finish,
        onBlobFailed = downloads::failed,
    ),
    "DeeptutorBridge",
)
```

> 接线时移除 Task 5 中旧的 `addJavascriptInterface` 调用，避免重复注册。

补充：Android 8–9 写公共 Downloads 需要存储权限，在 `MainActivity.onCreate` 末尾追加：

```kotlin
if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
    android.content.pm.PackageManager.PERMISSION_GRANTED
) {
    requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 9001)
}
```

- [ ] **Step 8: 验证并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

真机检查：笔记导出、闪卡导出、练习模板下载均落入 Downloads 且可打开。

```bash
git add apps/android
git commit -m "feat(android): 支持 http 与 blob 下载落盘"
```

---

## Task 9：外部链接与 window.open

**Files:**
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/ShellWebViewClient.kt`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`

- [ ] **Step 1: 写 `ShellWebViewClient.kt`**

```kotlin
package dev.raaa.deeptutor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class ShellWebViewClient(
    private val context: Context,
    private val serverUrl: () -> String,
    private val onMainFrameError: (String) -> Unit,
    private val fallbackScript: String? = null,
) : WebViewClient() {

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // 旧内核不支持 DOCUMENT_START_SCRIPT 时，在这里补注入
        fallbackScript?.let { view?.evaluateJavascript(it, null) }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (ServerUrl.isSameOrigin(serverUrl(), target)) return false
        openExternally(target)
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onMainFrameError(error.description.toString())
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            onMainFrameError("HTTP ${errorResponse.statusCode}")
        }
    }

    private fun openExternally(target: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // 没有可处理该协议的 App 时静默忽略
        }
    }
}
```

- [ ] **Step 2: 在 `MainActivity` 接入并处理新窗口**

```kotlin
webView.webViewClient = ShellWebViewClient(
    context = this,
    serverUrl = { store.serverUrl.orEmpty() },
    onMainFrameError = { message -> runOnUiThread { showLoadError(message) } },
    fallbackScript = currentFallbackScript(),
)

webView.settings.setSupportMultipleWindows(true)
webView.webChromeClient = object : ShellChromeClient(uploadPicker, { value ->
    progress.progress = value
    progress.visibility = if (value in 1..99) View.VISIBLE else View.GONE
}) {
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean {
        // target=_blank 不经过 shouldOverrideUrlLoading，这里取命中链接交给系统浏览器
        val href = view?.hitTestResult?.extra
        if (!href.isNullOrBlank() && !href.startsWith("blob:")) {
            try {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(href))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                // 没有应用可以处理该链接时忽略
            }
        }
        return false
    }
}
```

> `showLoadError` 在 Task 10 实现；本步骤先加入空实现 `private fun showLoadError(message: String) {}`，Task 10 替换为真实错误页。

- [ ] **Step 3: 验证并提交**

```powershell
.\gradlew.bat :app:assembleDebug
```

真机检查：点击页面内的外部链接会跳系统浏览器，App 内页面不跳走；返回 App 后状态保留。

```bash
git add apps/android
git commit -m "feat(android): 外链交给系统浏览器并拦截 window.open"
```

---

## Task 10：错误页、悬浮按钮与设置面板

**Files:**
- Create: `apps/android/app/src/main/res/layout/view_load_error.xml`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/LoadErrorView.kt`
- Create: `apps/android/app/src/main/res/layout/view_floating_settings.xml`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/FloatingSettingsButton.kt`
- Create: `apps/android/app/src/main/java/dev/raaa/deeptutor/SettingsSheet.kt`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`

- [ ] **Step 1: 写 `view_load_error.xml` 与 `LoadErrorView.kt`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="32dp">

    <ImageView
        android:layout_width="72dp"
        android:layout_height="72dp"
        android:contentDescription="@string/app_name"
        android:src="@mipmap/ic_launcher" />

    <TextView
        android:id="@+id/errorMessage"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="20dp"
        android:gravity="center"
        android:textAppearance="?attr/textAppearanceBodyLarge" />

    <Button
        android:id="@+id/retryButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="20dp"
        android:text="@string/error_retry" />

    <Button
        android:id="@+id/settingsButton"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/error_settings" />
</LinearLayout>
```

```kotlin
package dev.raaa.deeptutor

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView

/** 覆盖在 WebView 上的加载失败视图。 */
class LoadErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val messageView: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_load_error, this, true)
        messageView = findViewById(R.id.errorMessage)
    }

    fun bind(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
        messageView.text = message
        findViewById<android.widget.Button>(R.id.retryButton).setOnClickListener { onRetry() }
        findViewById<android.widget.Button>(R.id.settingsButton).setOnClickListener { onSettings() }
    }
}
```

- [ ] **Step 2: 写悬浮按钮**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:background="@drawable/bg_fab">

    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:layout_gravity="center"
        android:contentDescription="@string/settings_title"
        android:src="@drawable/ic_settings" />
</FrameLayout>
```

`res/drawable/bg_fab.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#CC000000" />
</shape>
```

```kotlin
package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/** 可拖动、自动贴边的设置入口，不遮挡正文时几乎无感。 */
@SuppressLint("ViewConstructor")
class FloatingSettingsButton(
    context: Context,
    private val store: ServerStore,
    private val onClick: () -> Unit,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragged = false

    init {
        inflate(context, R.layout.view_floating_settings, this)
        alpha = 0.85f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged &&
                    (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)
                ) {
                    dragged = true
                }
                if (dragged) {
                    x = event.rawX - width / 2f
                    y = event.rawY - height / 2f
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) snapToEdge() else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragged) snapToEdge()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge() {
        val parentView = parent as? View ?: return
        val targetX = if (x + width / 2 < parentView.width / 2f) 0f else (parentView.width - width).toFloat()
        animate().x(targetX).setDuration(180).withEndAction {
            animate().alpha(0.3f).setDuration(200).start()
        }.start()
        store.fabX = targetX.toInt()
        store.fabY = y.toInt()
    }

    override fun performClick(): Boolean {
        animate().alpha(0.85f).setDuration(120).start()
        onClick()
        super.performClick()
        return true
    }
}
```

- [ ] **Step 3: 写 `SettingsSheet.kt`**

```kotlin
package dev.raaa.deeptutor

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 原生设置面板：改地址、清站点数据、重新加载、关于。 */
class SettingsSheet(
    private val context: Context,
    private val store: ServerStore,
    private val webView: WebView,
    private val onServerChanged: (String) -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.sheet_settings, null)
        val layout = view.findViewById<TextInputLayout>(R.id.serverLayout)
        val input = view.findViewById<TextInputEditText>(R.id.serverInput)
        input.setText(store.serverUrl.orEmpty())

        view.findViewById<android.view.View>(R.id.saveServer).setOnClickListener {
            val normalized = ServerUrl.normalize(input.text?.toString().orEmpty())
            if (normalized == null) {
                layout.error = context.getString(R.string.connect_invalid)
                return@setOnClickListener
            }
            store.serverUrl = normalized
            dialog.dismiss()
            onServerChanged(normalized)
        }

        view.findViewById<android.view.View>(R.id.reloadPage).setOnClickListener {
            dialog.dismiss()
            webView.reload()
        }

        view.findViewById<android.view.View>(R.id.clearData).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            store.serverUrl = null
            dialog.dismiss()
            onServerChanged("")
        }

        view.findViewById<android.widget.TextView>(R.id.aboutLine).text =
            context.getString(R.string.settings_about, BuildConfig.VERSION_NAME, store.serverUrl.orEmpty())

        dialog.setContentView(view)
        dialog.show()
    }
}
```

`sheet_settings.xml`（完整结构）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/settings_title"
        android:textAppearance="?attr/textAppearanceTitleMedium" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/serverLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:hint="@string/connect_url_hint">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/serverInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textUri"
            android:maxLines="1" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/saveServer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/settings_save_server" />

    <Button
        android:id="@+id/reloadPage"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/settings_reload" />

    <Button
        android:id="@+id/clearData"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/settings_clear_data" />

    <TextView
        android:id="@+id/aboutLine"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textAppearance="?attr/textAppearanceBodySmall" />
</LinearLayout>
```

- [ ] **Step 4: 在 `MainActivity` 中接入三处 UI**

```kotlin
private lateinit var errorView: LoadErrorView
private lateinit var fab: FloatingSettingsButton

// 必须在 webView.loadUrl(serverUrl) 之前创建，避免加载失败回调早于视图初始化
val root = findViewById<android.widget.FrameLayout>(R.id.root)
errorView = LoadErrorView(this).apply { visibility = View.GONE }
root.addView(errorView, android.widget.FrameLayout.LayoutParams(
    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
))
fab = FloatingSettingsButton(this, store) { SettingsSheet(this, store, webView, ::onServerChanged).show() }
val fabSize = (40 * resources.displayMetrics.density).toInt()
root.addView(fab, android.widget.FrameLayout.LayoutParams(fabSize, fabSize))
restoreFabPosition()

// 替换 Task 9 的空实现
private fun showLoadError(message: String) {
    errorView.visibility = View.VISIBLE
    errorView.bind(
        message = message,
        onRetry = {
            errorView.visibility = View.GONE
            webView.reload()
        },
        onSettings = { SettingsSheet(this, store, webView, ::onServerChanged).show() },
    )
}

private fun onServerChanged(url: String) {
    if (url.isBlank()) {
        startActivity(android.content.Intent(this, ConnectActivity::class.java))
        finish()
        return
    }
    errorView.visibility = View.GONE
    webView.loadUrl(url)
}

private fun restoreFabPosition() {
    fab.post {
        val root = findViewById<android.widget.FrameLayout>(R.id.root)
        val margin = (16 * resources.displayMetrics.density).toInt()
        val x = if (store.fabX == Int.MIN_VALUE) root.width - fab.width - margin else store.fabX
        val y = if (store.fabY == Int.MIN_VALUE) root.height - fab.height - margin * 4 else store.fabY
        fab.x = x.toFloat()
        fab.y = y.toFloat()
    }
}
```

`strings.xml` 追加：

```xml
<string name="settings_title">设置</string>
<string name="settings_save_server">保存地址并重新加载</string>
<string name="settings_reload">重新加载页面</string>
<string name="settings_clear_data">清除站点数据</string>
<string name="settings_about">App %1$s · 当前地址 %2$s</string>
<string name="error_retry">重试</string>
<string name="error_settings">服务器设置</string>
```

- [ ] **Step 5: 验证并提交**

```powershell
.\gradlew.bat :app:assembleDebug
```

真机检查：断网后显示错误页，重试可用；悬浮按钮可拖动、自动贴边变淡；设置面板能改地址并生效；清除数据后回到连接页。

```bash
git add apps/android
git commit -m "feat(android): 新增错误页、悬浮设置按钮与原生设置面板"
```

---

## Task 11：应用图标与启动页

**Files:**
- Create: `apps/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `apps/android/app/src/main/res/mipmap-*/ic_launcher.png` 与 `ic_launcher_foreground.png`（由 `web/public/brand/color-logo.png` 缩放生成）
- Create: `apps/android/app/src/main/res/values/ic_launcher_background.xml`
- Modify: `apps/android/app/src/main/java/dev/raaa/deeptutor/MainActivity.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/res/values/themes.xml`
- Modify: `apps/android/app/src/main/res/layout/activity_connect.xml`（图标改为 `@mipmap/ic_launcher`）

- [ ] **Step 1: 准备图标资源**

从 `web/public/brand/color-logo.png` 生成 **192×192** 与 **512×512** 两个 PNG（例如用 `System.Drawing` 或任意图片工具缩放），分别放入 `res/mipmap-xxxhdpi/ic_launcher.png` 与 `res/mipmap-anydpi-v26/` 所需的前景资源；`sizes` 必须与真实像素尺寸一致，否则 PWA/启动器会拒绝该图标。

自适应图标使用 `ic_launcher_foreground.png`（432×432，四周留 72dp 安全区）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0F1115</color>
</resources>
```

- [ ] **Step 2: 启用 SplashScreen**

`MainActivity.onCreate` 第一行（`super.onCreate` 之后）安装启动页，并按「首帧渲染完成或最多 2 秒」关闭：

```kotlin
val startedAt = android.os.SystemClock.uptimeMillis()
val splash = androidx.core.splashscreen.SplashScreen.installSplashScreen(this)
splash.setKeepOnScreenCondition {
    !::webView.isInitialized ||
        (webView.contentHeight == 0 && android.os.SystemClock.uptimeMillis() - startedAt < 2_000)
}
```

> 不要用 `webView.post { 关掉 }`，那会在下一帧立刻关闭启动页，失去意义。

`themes.xml` 追加：

```xml
<style name="Theme.DeepTutor.Splash" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/ic_launcher_background</item>
    <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher_foreground</item>
    <item name="postSplashScreenTheme">@style/Theme.DeepTutor</item>
</style>
```

`AndroidManifest.xml` 的 `MainActivity` 主题改为 `@style/Theme.DeepTutor.Splash`。

- [ ] **Step 3: 构建验证并提交**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: 冷启动先显示品牌启动页，随后进入 Web 页面。

```bash
git add apps/android
git commit -m "feat(android): 添加应用图标与启动页"
```

---

## Task 12：PWA manifest（Web 端）

**Files:**
- Create: `web/app/manifest.ts`

- [ ] **Step 1: 写 `web/app/manifest.ts`**

```ts
import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "DeepTutor",
    short_name: "DeepTutor",
    description: "Agent-native intelligent learning companion",
    start_url: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#0f1115",
    icons: [
      { src: "/brand/color-logo.png", sizes: "192x192", type: "image/png" },
      { src: "/brand/color-logo.png", sizes: "512x512", type: "image/png" },
      { src: "/brand/color-logo.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
```

- [ ] **Step 2: 验证生成**

```powershell
cd web
npm run build
```

Expected: 构建成功；`.next` 产物中可找到 `manifest.webmanifest` 路由。Next.js 会自动在页面 `<head>` 注入 manifest link，无需改 `layout.tsx`。

- [ ] **Step 3: 提交**

```bash
git add web/app/manifest.ts
git commit -m "feat(web): 新增 PWA manifest"
```

---

## Task 13：CI 构建与发布

**Files:**
- Create: `.github/workflows/android-release.yml`
- Create: `apps/android/README.md`

- [ ] **Step 1: 写 workflow**

```yaml
name: Android Release

on:
  release:
    types: [published]
  workflow_dispatch:
  pull_request:
    paths:
      - "apps/android/**"
      - ".github/workflows/android-release.yml"
  push:
    branches: [dev]
    paths:
      - "apps/android/**"

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: apps/android
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Verify tag matches version
        if: github.event_name == 'release'
        run: |
          VERSION=$(python3 -c "import re;print(re.search(r'__version__\s*=\s*\"([^\"]+)\"', open('../../deeptutor/__version__.py').read()).group(1))")
          if [ "v$VERSION" != "${GITHUB_REF_NAME}" ]; then
            echo "tag ${GITHUB_REF_NAME} != v$VERSION"; exit 1
          fi

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - uses: android-actions/setup-android@v3

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Decode keystore
        if: ${{ secrets.ANDROID_KEYSTORE_BASE64 != '' }}
        run: echo "${{ secrets.ANDROID_KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/release.keystore"

      - name: Build release
        if: ${{ secrets.ANDROID_KEYSTORE_BASE64 != '' }}
        env:
          ANDROID_KEYSTORE_PATH: ${{ runner.temp }}/release.keystore
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
          ORG_GRADLE_PROJECT_deeptutorDefaultServerUrl: ${{ vars.DEEPTUTOR_ANDROID_DEFAULT_URL }}
        run: |
          VERSION=$(python3 -c "import re;print(re.search(r'__version__\s*=\s*\"([^\"]+)\"', open('../../deeptutor/__version__.py').read()).group(1))")
          gradle :app:assembleRelease
          cp app/build/outputs/apk/release/app-release.apk "$RUNNER_TEMP/DeepTutor-Android-v$VERSION.apk"

      - name: Build debug fallback
        if: ${{ secrets.ANDROID_KEYSTORE_BASE64 == '' }}
        run: |
          VERSION=$(python3 -c "import re;print(re.search(r'__version__\s*=\s*\"([^\"]+)\"', open('../../deeptutor/__version__.py').read()).group(1))")
          echo "::warning::未配置签名 Secrets，产出 debug APK"
          gradle :app:assembleDebug
          cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/DeepTutor-Android-v$VERSION-debug.apk"

      - uses: actions/upload-artifact@v4
        with:
          name: android-apk
          path: ${{ runner.temp }}/*.apk

      - name: Attach to release
        if: github.event_name == 'release'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: gh release upload "${{ github.ref_name }}" "${{ runner.temp }}"/*.apk --clobber
```

- [ ] **Step 2: 写 `apps/android/README.md`（签名密钥生成）**

````markdown
# DeepTutor Android 客户端

构建与发布由 `.github/workflows/android-release.yml` 完成。

## 生成本地签名密钥

```bash
keytool -genkeypair -v -keystore release.keystore -alias deeptutor \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

## 配置仓库 Secrets

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | `deeptutor` |
| `ANDROID_KEY_PASSWORD` | key 密码 |

未配置 `ANDROID_KEYSTORE_BASE64` 时，工作流产出 `-debug.apk` 并打印警告。

## 本地构建

```bash
cd apps/android
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
````

- [ ] **Step 3: 提交**

```bash
git add .github/workflows/android-release.yml apps/android/README.md
git commit -m "ci(android): 发布 APK 并支持签名降级"
```

---

## Task 14：端到端验收

**Files:** 无代码改动。

- [ ] **Step 1: 完整构建**

```powershell
cd apps/android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: 单测全部通过，`BUILD SUCCESSFUL`。

- [ ] **Step 2: 按规格 §11 逐项真机验收**

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 首次安装启动 | 出现连接页，输入地址后加载成功 |
| 2 | 再次启动 | 直接进入上次地址，免登录 |
| 3 | 系统栏 | 无地址栏；深浅色图标正确 |
| 4 | 返回键 | 逐级后退；首页双击退出 |
| 5 | 混合附件入口 | 弹出拍照 / 相册 / 文件三选一 |
| 5b | 纯图片入口 | 直接进入系统相册，可多选 |
| 5c | 取消选择 | 输入框恢复可用 |
| 6 | http(s) 下载 | 落入 Downloads |
| 7 | blob 下载 | 笔记/闪卡/练习模板可打开 |
| 8 | 外部链接 | 跳系统浏览器 |
| 9 | 下拉刷新 | 仅顶部触发，聊天滚动不误触 |
| 10 | 断网启动 | 错误页可重试 |
| 11 | 悬浮按钮 | 可拖动贴边，设置可改地址 |
| 12 | 清除站点数据 | 回到连接页 |
| 13 | 旋转屏幕 | 页面不重建 |

- [ ] **Step 3: 记录验收结果**

把验收结果追加到 `docs-for-dev/ANDROID_WEBVIEW_CLIENT.md` 末尾的"验收记录"小节（新建该小节），只记录事实与失败项，不写推测。

- [ ] **Step 4: 提交**

```bash
git add docs-for-dev/ANDROID_WEBVIEW_CLIENT.md
git commit -m "docs(android): 记录首轮真机验收结果"
```

---

## 计划自查

**规格覆盖：**

| 规格小节 | 对应任务 |
| --- | --- |
| §6.1 首次启动与地址 | Task 3、Task 4 |
| §6.2 加载与全屏 | Task 5、Task 11 |
| §6.3 返回键 | Task 6 |
| §6.4 悬浮设置按钮 | Task 10 |
| §6.5 文件上传 | Task 7 |
| §6.6 下载 | Task 8 |
| §6.7 外链与 window.open | Task 9 |
| §6.8 下拉刷新 | Task 6 |
| §6.9 错误页 | Task 10 |
| §6.10 主题联动 | Task 5 |
| §6.11 设置面板 | Task 10 |
| §6.12 生命周期 | Task 5 |
| §7 数据存储 | Task 4（ServerStore） |
| §9 构建发布 | Task 2、Task 13 |
| §10 PWA manifest | Task 12 |
| §11 验收 | Task 14 |

**已知缺口：** 无。Android 8–9 的存储权限申请已并入 Task 8；`DeepTutorApp` 在 Task 2 创建；旧内核的注入兜底在 Task 5 保存、Task 9 消费。
