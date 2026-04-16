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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
                        it.surfaceProvider = previewView.surfaceProvider
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

        FloatingActionButton(
            onClick = {
                captureAndOcr(imageCapture, context) { text ->
                    onTextExtracted(text)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(72.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "Capture",
                modifier = Modifier.size(32.dp),
            )
        }
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
