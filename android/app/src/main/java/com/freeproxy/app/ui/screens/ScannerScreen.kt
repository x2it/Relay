package com.freeproxy.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Seed
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * 内置扫码界面（CameraX + ML Kit）
 * - 实时扫码：扫到内容回调原始文本，由上层解析（ShareLinkParser / ProxyUrlParser）
 * - 相册导入：[ALBUM] 选截图，ML Kit 离线解析二维码（截屏分享场景）
 * - terminal 风格：[ESC] 关闭、[ALBUM] 相册、四角取景框、等宽提示文案
 */
@Composable
fun ScannerScreen(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // 扫到结果后暂停分析，避免连续回调
    var paused by remember { mutableStateOf(false) }
    val resultRef = remember { mutableStateOf(onResult) }
    resultRef.value = onResult

    // ===== 相册导入：独立解析 client（与相机流互不影响）=====
    val albumScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_DATA_MATRIX,
                )
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { albumScanner.close() } }
    }
    var albumErr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(albumErr) {
        if (albumErr != null) {
            delay(2500)
            albumErr = null
        }
    }
    val albumLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            albumErr = null
            val ex = Executors.newSingleThreadExecutor()
            ex.execute {
                runCatching {
                    val input = InputImage.fromFilePath(context, uri)
                    albumScanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            val text = barcodes.firstOrNull()?.rawValue
                            if (text.isNullOrBlank()) {
                                albumErr = "! no qr found"
                            } else {
                                paused = true
                                resultRef.value(text)
                            }
                            ex.shutdown()
                        }
                        .addOnFailureListener {
                            albumErr = "! read failed"
                            ex.shutdown()
                        }
                }.onFailure {
                    albumErr = "! read failed"
                    ex.shutdown()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E12))) {
        if (hasPermission) {
            CameraAnalyzer(
                paused = paused,
                onBarcode = { text ->
                    if (!paused && text.isNotBlank()) {
                        paused = true
                        resultRef.value(text)
                    }
                },
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "> camera permission denied",
                    color = OnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                Text(
                    "可从相册导入二维码",
                    color = OnSurfaceDim.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "[ 重试授权 ]",
                    color = Seed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Seed, RoundedCornerShape(8.dp))
                        .clickable { permLauncher.launch(Manifest.permission.CAMERA) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        ScanOverlay(
            onDismiss = onDismiss,
            onAlbum = {
                albumLauncher.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            albumErr = albumErr,
        )
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraAnalyzer(
    paused: Boolean,
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_DATA_MATRIX,
                )
                .build()
        )
    }
    val pausedRef = remember { mutableStateOf(paused) }
    pausedRef.value = paused
    val onBarcodeRef = remember { mutableStateOf(onBarcode) }
    onBarcodeRef.value = onBarcode

    DisposableEffect(Unit) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !pausedRef.value) {
                        val input = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                val text = barcodes.firstOrNull()?.rawValue
                                if (!text.isNullOrBlank()) onBarcodeRef.value(text)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            runCatching { executor.shutdown() }
            runCatching { scanner.close() }
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** terminal 取景框 overlay：[ESC] / [ALBUM]、四角括号、底部提示 */
@Composable
private fun ScanOverlay(
    onDismiss: () -> Unit,
    onAlbum: () -> Unit,
    albumErr: String?,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "[ESC]",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "> scan",
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "[ALBUM]",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = onAlbum)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // 四角取景括号
        Box(Modifier.align(Alignment.Center).size(250.dp)) {
            val len = 26.dp
            val th = 2.5.dp
            Box(Modifier.align(Alignment.TopStart).size(len, th).background(Seed))
            Box(Modifier.align(Alignment.TopStart).size(th, len).background(Seed))
            Box(Modifier.align(Alignment.TopEnd).size(len, th).background(Seed))
            Box(Modifier.align(Alignment.TopEnd).size(th, len).background(Seed))
            Box(Modifier.align(Alignment.BottomStart).size(len, th).background(Seed))
            Box(Modifier.align(Alignment.BottomStart).size(th, len).background(Seed))
            Box(Modifier.align(Alignment.BottomEnd).size(len, th).background(Seed))
            Box(Modifier.align(Alignment.BottomEnd).size(th, len).background(Seed))
        }

        // 底部提示：相册解析出错时替换为错误文案
        albumErr?.let { err ->
            Text(
                err,
                color = Bad,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
        } ?: Text(
            "对准 vmess / trojan / vless / ss 二维码 · 或 [ALBUM] 从相册导入",
            color = Color.White.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }
}
