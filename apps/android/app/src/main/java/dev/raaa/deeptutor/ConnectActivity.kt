package dev.raaa.deeptutor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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

        val input = findViewById<EditText>(R.id.urlInput)
        val error = findViewById<TextView>(R.id.urlError)
        val button = findViewById<MaterialButton>(R.id.connectButton)

        if (input.text.isNullOrBlank()) {
            input.setText(BuildConfig.DEFAULT_SERVER_URL)
        }

        button.setOnClickListener {
            val normalized = ServerUrl.normalize(input.text?.toString().orEmpty())
            if (normalized == null) {
                error.text = getString(R.string.connect_invalid)
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
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
        val error = findViewById<TextView>(R.id.urlError)
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
