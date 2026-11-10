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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    private lateinit var errorView: LoadErrorView
    private lateinit var fab: FloatingSettingsButton
    private var atPageTop = true
    private var lastBackAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首帧渲染完成或最多 2 秒后关闭启动页
        val startedAt = android.os.SystemClock.uptimeMillis()
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
            (!::webView.isInitialized || webView.contentHeight == 0) && elapsed < 2_000
        }

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

        installDocumentStartScript()
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

        errorView = LoadErrorView(this).apply { visibility = View.GONE }
        root.addView(
            errorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        fab = FloatingSettingsButton(this, store) {
            SettingsSheet(this, store, webView, ::onServerChanged).show()
        }
        val fabSize = (40 * resources.displayMetrics.density).toInt()
        root.addView(fab, FrameLayout.LayoutParams(fabSize, fabSize))
        restoreFabPosition()

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
            val root = findViewById<FrameLayout>(R.id.root)
            val margin = (16 * resources.displayMetrics.density).toInt()
            val x = if (store.fabX == Int.MIN_VALUE) root.width - fab.width - margin else store.fabX
            val y = if (store.fabY == Int.MIN_VALUE) root.height - fab.height - margin * 4 else store.fabY
            fab.x = x.toFloat()
            fab.y = y.toFloat()
        }
    }

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
