package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var store: ServerStore
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var uploadPicker: UploadPicker
    private var atPageTop = true
    private var lastBackAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // registerForActivityResult 必须早于 Activity STARTED，故在此提前构造
        uploadPicker = UploadPicker(this)

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
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString DeepTutorAndroid/${BuildConfig.VERSION_NAME}"
        }

        webView.webChromeClient = ShellChromeClient(uploadPicker) { value ->
            progress.progress = value
            progress.visibility = if (value in 1..99) View.VISIBLE else View.GONE
        }

        val downloads = DownloadBridge(this) { message ->
            runOnUiThread { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
        }
        webView.setDownloadListener(downloads)
        webView.addJavascriptInterface(
            ShellBridge(
                themeCallback = { dark -> runOnUiThread { applySystemBars(dark) } },
                scrollCallback = { top -> atPageTop = top },
                blobBegin = downloads::begin,
                blobChunk = downloads::append,
                blobFinish = downloads::finish,
                blobFailed = downloads::failed,
            ),
            "DeeptutorBridge",
        )

        installDocumentStartScript()
        applySystemBars(isSystemDark())

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
            android.widget.Toast.makeText(
                this@MainActivity,
                R.string.back_to_exit,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        webView.loadUrl(serverUrl)

        // Android 8–9 写公共 Downloads 需要存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 9001)
        }
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
