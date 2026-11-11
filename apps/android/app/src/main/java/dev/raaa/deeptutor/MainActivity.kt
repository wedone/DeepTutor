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
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var store: ServerStore
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var uploadPicker: UploadPicker
    private lateinit var customLayer: CustomLayer
    private val mathLiveLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(MathLiveAssetHandler.DOMAIN)
            .addPathHandler(MathLiveAssetHandler.PATH_PREFIX, MathLiveAssetHandler(assets))
            .build()
    }
    private lateinit var errorView: LoadErrorView
    private lateinit var fab: FloatingSettingsButton
    private lateinit var mathTrigger: android.widget.TextView
    private var mathKeyboard: MathKeyboardView? = null
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
        customLayer = CustomLayer(this)
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
            assetLoader = mathLiveLoader,
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
                hideKeyboardCallback = { hideKeyboard() },
            ),
            "DeeptutorBridge",
        )

        applySystemBars(isSystemDark())
        applyImmersive(store.immersive)

        refresh = findViewById(R.id.refresh)
        refresh.setOnRefreshListener {
            webView.reload()
            refresh.isRefreshing = false
        }
        refresh.setOnChildScrollUpCallback { _, _ -> !atPageTop }

        onBackPressedDispatcher.addCallback(this) {
            if (mathKeyboard?.visibility == View.VISIBLE) {
                hideMathKeyboard()
                return@addCallback
            }
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
            SettingsSheet(this, store, webView, ::onServerChanged, ::onImmersiveChanged, ::onCustomLayerChanged).show()
        }
        val fabSize = (40 * resources.displayMetrics.density).toInt()
        root.addView(fab, FrameLayout.LayoutParams(fabSize, fabSize))
        restoreFabPosition()

        mathTrigger = buildMathTrigger()
        root.addView(
            mathTrigger,
            FrameLayout.LayoutParams(fabSize, fabSize).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, dp(16), dp(168))
            },
        )

        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                mathKeyboard?.visibility == View.VISIBLE
            ) {
                hideMathKeyboard()
            }
            false
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
        val scripts = mutableListOf(
            assets.open("shell.js").bufferedReader().use { it.readText() },
        )
        customLayer.css()?.let { scripts += CustomLayer.cssScript(it) }
        customLayer.script()?.let { scripts += CustomLayer.jsScript(it) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // 分段注入，单段出错不影响壳层自身脚本
            scripts.forEach { WebViewCompat.addDocumentStartJavaScript(webView, it, setOf("*")) }
        } else {
            // 旧内核：由 ShellWebViewClient 在 onPageFinished 兜底注入
            fallbackScript = scripts.joinToString("\n")
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
            onSettings = { SettingsSheet(this, store, webView, ::onServerChanged, ::onImmersiveChanged, ::onCustomLayerChanged).show() },
        )
    }

    private fun onImmersiveChanged(enabled: Boolean) {
        store.immersive = enabled
        applyImmersive(enabled)
    }

    /** 保存注入层地址并后台拉取；成功后重载页面使新样式生效。 */
    private fun onCustomLayerChanged(url: String) {
        store.customLayerUrl = url.trim().ifBlank { null }
        val target = store.customLayerUrl
        Thread {
            val ok = if (target == null) {
                customLayer.clear()
                true
            } else {
                customLayer.refresh(target)
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (ok) R.string.custom_layer_saved else R.string.custom_layer_failed,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                if (ok) webView.reload()
            }
        }.start()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildMathTrigger(): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = "Σ"
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 20f
        tv.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#4F8CFF"))
        }
        tv.alpha = 0.85f
        tv.contentDescription = "数学公式"
        tv.setOnClickListener { toggleMathKeyboard() }
        return tv
    }

    private fun toggleMathKeyboard() {
        if (mathKeyboard?.visibility == View.VISIBLE) hideMathKeyboard() else showMathKeyboard()
    }

    private fun showMathKeyboard() {
        hideKeyboard()
        // 面板显示期间不让系统输入法挤压布局
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val root = findViewById<FrameLayout>(R.id.root)
        val panel = mathKeyboard ?: MathKeyboardView(
            context = this,
            assetLoader = mathLiveLoader,
            insertCallback = ::insertMath,
            heightCallback = ::applyMathPanelHeight,
        ).also {
            mathKeyboard = it
            root.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(360),
                ).apply { gravity = android.view.Gravity.BOTTOM },
            )
        }
        panel.visibility = View.VISIBLE
        panel.setDark(isSystemDark())
        panel.bringToFront()
    }

    private fun hideMathKeyboard() {
        mathKeyboard?.visibility = View.GONE
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** 键盘页面上报的 CSS 像素高度，换算为 dp 后约束在合理区间。 */
    private fun applyMathPanelHeight(cssHeight: Int) {
        val panel = mathKeyboard ?: return
        if (cssHeight < 200) return
        val density = resources.displayMetrics.density
        val maxPx = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val target = (cssHeight * density).toInt().coerceIn(dp(240), maxPx)
        panel.layoutParams = panel.layoutParams.apply { height = target }
        panel.requestLayout()
    }

    /** 把数学键盘选中的公式写入聊天输入框。 */
    private fun insertMath(latex: String, mode: String) {
        val js = "window.__dtInsertMath && window.__dtInsertMath(" +
            org.json.JSONObject.quote(latex) + "," + org.json.JSONObject.quote(mode) + ")"
        webView.evaluateJavascript(js, null)
    }

    private fun applySystemBars(dark: Boolean) {
        // API 35 下 statusBarColor 已失效，由根布局背景承担系统栏底色
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.decorView.setBackgroundColor(if (dark) Color.parseColor("#101114") else Color.WHITE)
    }

    /** 沉浸模式：隐藏状态栏，边缘下滑可临时调出；关闭后恢复常显。 */
    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
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

    override fun onDestroy() {
        mathKeyboard?.destroy()
        mathKeyboard = null
        super.onDestroy()
    }
}
