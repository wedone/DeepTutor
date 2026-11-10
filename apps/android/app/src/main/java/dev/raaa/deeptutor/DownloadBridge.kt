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
