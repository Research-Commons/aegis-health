package com.aegis.health.camera

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraPipeline"

/**
 * CameraX + ML Kit Text Recognition pipeline.
 *
 * Provides a Compose-native camera preview with a capture button that
 * runs on-device OCR and returns the extracted text.
 */

@Composable
fun CameraPreviewWithCapture(
    onTextExtracted: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageCapture = remember { ImageCapture.Builder().build() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val provider = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }
        cameraProvider = provider
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        cameraProvider?.let { provider ->
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                    }

                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Reticle brackets — frame the active-ingredient region.
        ScannerBrackets(modifier = Modifier.fillMaxSize())

        // Hint at the top.
        if (hint != null) {
            Text(
                text = hint,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth(),
            )
        }

        // Cancel X — top-left, semi-transparent black pill.
        if (onCancel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 18.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x59000000))
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // White capture button per spec — 72dp white circle, 4dp translucent ring.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .size(72.dp)
                .border(4.dp, Color(0x66FFFFFF), CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    captureAndOcr(imageCapture, context) { text ->
                        onTextExtracted(text)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "Capture",
                tint = Color(0xFF1A1816),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ScannerBrackets(modifier: Modifier = Modifier) {
    // Direction A · Clinical Calm — coral brackets to match the system accent.
    val mintAccent = com.aegis.health.ui.theme.AegisCoral
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Active-ingredient framing region — 70dp horizontal inset, 32% vertical inset.
        val padX = 70.dp.toPx().coerceAtMost(w * 0.18f)
        val padY = h * 0.32f
        val left = padX
        val right = w - padX
        val top = padY
        val bottom = h - padY

        val brk = 28.dp.toPx()
        val stroke = 3.dp.toPx()

        fun drawCorner(x: Float, y: Float, dx: Int, dy: Int) {
            // dx,dy are ±1 indicating the direction the bracket arms extend.
            drawLine(
                color = mintAccent,
                start = androidx.compose.ui.geometry.Offset(x, y),
                end = androidx.compose.ui.geometry.Offset(x + dx * brk, y),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                color = mintAccent,
                start = androidx.compose.ui.geometry.Offset(x, y),
                end = androidx.compose.ui.geometry.Offset(x, y + dy * brk),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        drawCorner(left,  top,    +1, +1)
        drawCorner(right, top,    -1, +1)
        drawCorner(left,  bottom, +1, -1)
        drawCorner(right, bottom, -1, -1)
    }
}

private fun captureAndOcr(
    imageCapture: ImageCapture,
    context: Context,
    onResult: (String) -> Unit,
) {
    val executor = Executors.newSingleThreadExecutor()
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    onResult("")
                    return
                }

                val inputImage = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                )

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        onResult(result.text)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "OCR failed", e)
                        onResult("")
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                onResult("")
            }
        },
    )
}

/**
 * Flow-based API for capturing and extracting text from the camera.
 * Each emission is the OCR result from a single capture.
 */
fun captureAndExtractText(
    imageCapture: ImageCapture,
    context: Context,
): Flow<String> = callbackFlow {
    captureAndOcr(imageCapture, context) { text ->
        trySend(text)
    }
    awaitClose { }
}
