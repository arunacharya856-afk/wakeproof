package com.dixit.wakeproof

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Counts push-ups with the front camera: the phone lies face-up under your face, and your face
 * gets much bigger in the picture at the bottom of each rep. Runs fully on the phone (ML Kit), no internet.
 *
 * "size" = face width as a fraction of the picture width (0..1). When the face is so close the
 * detector loses it right after it was big, that also counts as "close".
 */
class PushupCounter(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val onRep: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var provider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var detector: FaceDetector? = null
    var running = false
        private set

    // Rep detection state
    private var down = false
    private var topSize = 0f      // face size at the top of the push-up (adapts over time)
    private var lastSize = 0f
    private var lastSeenAt = 0L

    fun start() {
        if (running) return
        running = true
        down = false
        topSize = 0f
        lastSize = 0f
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
        )
        executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                if (!running) return@addListener
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor!!) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                onStatus("Camera on — get your face in the preview")
            } catch (e: Exception) {
                running = false
                onError("Camera couldn't start: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        running = false
        try { provider?.unbindAll() } catch (e: Exception) {}
        try { detector?.close() } catch (e: Exception) {}
        detector = null
        executor?.shutdown()
        executor = null
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val det = detector
        if (media == null || det == null || !running) {
            proxy.close()
            return
        }
        val rot = proxy.imageInfo.rotationDegrees
        // Width of the picture as the detector sees it (after rotation).
        val frameW = if (rot == 90 || rot == 270) proxy.height else proxy.width
        val input = InputImage.fromMediaImage(media, rot)
        det.process(input)
            .addOnSuccessListener(ContextCompat.getMainExecutor(activity)) { faces ->
                val biggest = faces.maxByOrNull { it.boundingBox.width() }
                val size = biggest?.let { (it.boundingBox.width().toFloat() / frameW).coerceIn(0f, 1.5f) }
                onSample(size)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onSample(raw: Float?) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val size: Float = when {
            raw != null -> {
                lastSize = raw
                lastSeenAt = now
                raw
            }
            // Lost the face right after it was big = you're too close for it to see = bottom of the rep.
            lastSize > 0.35f && now - lastSeenAt < 1500 -> 1.0f
            else -> {
                onStatus(if (down) "Push back up" else "No face — keep your face over the phone")
                return
            }
        }

        if (topSize == 0f) topSize = size

        if (!down) {
            // Learn where "the top" is: follow smaller sizes quickly, larger ones very slowly.
            topSize = if (size < topSize) topSize * 0.5f + size * 0.5f else topSize * 0.98f + size * 0.02f
            val downAt = maxOf(topSize * 1.6f, topSize + 0.12f)
            if (size > downAt) {
                down = true
                onRep()
            }
        } else {
            val upAt = maxOf(topSize * 1.25f, topSize + 0.05f)
            if (size < upAt) down = false
        }
        onStatus("Face size ${(size * 100).toInt()}% · ${if (down) "down ✓ now push up" else "up — go down"}")
    }
}
