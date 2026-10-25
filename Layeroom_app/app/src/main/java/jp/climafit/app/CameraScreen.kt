package jp.climafit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 写真でコーデ診断のカメラ（スマホの標準カメラと同じ並び）。
 * 真ん中：シャッター / 左：写真フォルダから選ぶ / 右：内側・外側のカメラを切り替える。
 * カメラの許可が無いときも、写真フォルダからは選べる。
 */
@Composable
internal fun CameraScreen(onCaptured: (ByteArray) -> Unit, onPickFromGallery: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denials by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) denials++
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var back by rememberSaveable { mutableStateOf(true) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }

    fun shoot() {
        val c = capture ?: return
        capturing = true
        val main = ContextCompat.getMainExecutor(context)
        c.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = runCatching { image.use { it.toUprightJpeg() } }.getOrNull()
                main.execute {
                    capturing = false
                    if (jpeg != null) onCaptured(jpeg) else cameraError = "写真を保存できませんでした。もう一度撮ってください。"
                }
            }

            override fun onError(exception: ImageCaptureException) {
                main.execute { capturing = false; cameraError = "撮影できませんでした。もう一度撮ってください。" }
            }
        })
    }

    Column(Modifier.fillMaxSize().background(Color.Black).testTag("camera-screen")) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("camera-close")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "カメラを閉じる", tint = Color.White)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !granted -> CameraMessage(
                    "カメラを使うには、カメラの許可が必要です。\n左下のボタンから、写真フォルダの写真も選べます。",
                    if (denials >= 2) "設定を開く" else "カメラを許可する",
                ) {
                    if (denials >= 2) {
                        // 「今後表示しない」になると許可の確認が出ないので、アプリの設定を開く
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                }
                cameraError != null -> CameraMessage(cameraError.orEmpty(), "もう一度試す") { cameraError = null }
                else -> CameraPreview(back, onReady = { capture = it }, onError = { cameraError = it })
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CameraSideButton(R.drawable.ic_image, "写真フォルダから選ぶ", "camera-gallery", enabled = !capturing, onClick = onPickFromGallery)
            Shutter(enabled = granted && cameraError == null && capture != null && !capturing, onClick = ::shoot)
            CameraSideButton(R.drawable.ic_refresh, "内側と外側のカメラを切り替える", "camera-switch",
                enabled = granted && cameraError == null && !capturing) { back = !back; capture = null }
        }
    }
}

/** カメラの映像。back が変わるたびに、使うカメラをつなぎ直す。 */
@Composable
private fun CameraPreview(back: Boolean, onReady: (ImageCapture?) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView({ previewView }, Modifier.fillMaxSize().testTag("camera-preview"))
    DisposableEffect(back, lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (!p.hasCamera(selector)) {
                    onError(if (back) "外側のカメラがありません" else "内側のカメラがありません")
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                onReady(imageCapture)
            } catch (e: Exception) {
                onError("カメラを起動できませんでした")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            onReady(null)
            provider?.unbindAll()
        }
    }
}

@Composable
private fun CameraMessage(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.padding(32.dp).testTag("camera-message"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onAction, modifier = Modifier.testTag("camera-message-action")) { Text(action, color = Color.White) }
    }
}

/** 真ん中の白い丸のシャッター。 */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(84.dp).border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape).padding(8.dp)
        .clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.4f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "撮影する" }.testTag("camera-shutter"))
}

/** 左右の丸いボタン（写真フォルダ・カメラの切り替え）。 */
@Composable
private fun CameraSideButton(icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(26.dp))
    }
}

/** 撮った写真を、画面で見た向きに回して JPEG にする（長辺 2000px まで）。 */
private fun ImageProxy.toUprightJpeg(): ByteArray {
    var bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    val scale = minOf(1f, 2000f / maxOf(bitmap.width, bitmap.height))
    if (rotation != 0 || scale < 1f) {
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }
}
