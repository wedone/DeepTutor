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
