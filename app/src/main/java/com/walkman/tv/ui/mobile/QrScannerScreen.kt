package com.walkman.tv.ui.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.walkman.tv.ui.theme.AppColors
import java.util.concurrent.Executors

/**
 * 扫码添加同步设备：CameraX 取景 + ZXing 解二维码（只用亮度 Y 平面，全离线，不用 Play 服务）。
 * 扫到 kitvmusic://sync 链接就回传给同步页配对；扫到别的码会提示不是同步码。
 * 没给相机权限时给手动输入的退路（同步页里本来就有手动添加）。
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hint by remember { mutableStateOf("把二维码放进框里") }
    // 解码在相机线程上跑，done 必须用 AtomicBoolean，不能直接写 Compose state。
    val doneFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) hint = "没给相机权限：回同步页用「手动添加设备」一样能配对"
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener(
                        {
                            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            var lastAttempt = 0L
                            analysis.setAnalyzer(executor) { image ->
                                try {
                                    val now = System.currentTimeMillis()
                                    // 每 300ms 试解一次，别把每一帧都喂给解码器。
                                    if (!doneFlag.get() && now - lastAttempt > 300) {
                                        lastAttempt = now
                                        decodeQr(image)?.let { text ->
                                            if (doneFlag.compareAndSet(false, true)) {
                                                onScanned(text)
                                            }
                                        }
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }.onFailure { Log.w("QrScanner", "bind failed", it) }
                        },
                        ContextCompat.getMainExecutor(ctx),
                    )
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 取景框提示
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(72.dp))
                Text(hint, color = AppColors.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
                ) {
                    Text("打开相机权限", fontSize = 14.sp)
                }
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
    }
}

/** 只用 Y（亮度）平面解二维码：二维码是黑白的，亮度就够了，省掉 UV 转换。 */
private fun decodeQr(image: ImageProxy): String? {
    val width = image.width
    val height = image.height
    val yPlane = image.planes[0]
    val yBuf = yPlane.buffer
    val rowStride = yPlane.rowStride
    val yBytes = ByteArray(width * height)
    for (row in 0 until height) {
        yBuf.position(row * rowStride)
        yBuf.get(yBytes, row * width, width)
    }
    val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
    return runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
