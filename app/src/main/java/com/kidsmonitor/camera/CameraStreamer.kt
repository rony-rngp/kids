package com.kidsmonitor.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class CameraStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (ByteArray) -> Unit
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK

    fun startCamera(initialFacing: Int) {
        currentFacing = initialFacing
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(facing: Int) {
        if (currentFacing != facing) {
            currentFacing = facing
            bindCameraUseCases()
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(currentFacing).build()

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalyzer())
            }

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (exc: Exception) {
            // Log or handle exceptions
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(image: ImageProxy) {
            image.toJpeg()?.let { onFrame(it) }
            image.close()
        }
    }

    private fun ImageProxy.toJpeg(): ByteArray? {
        if (format != ImageFormat.YUV_420_888) {
            return null
        }

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        //U and V are swapped because YuvImage expects YV12, which has V before U.
        //CameraX provides Y, U, V.
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        return out.toByteArray()
    }
}
