package com.kidsmonitor.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import android.util.Log
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

    // Reusable buffers for performance
    private var nv21: ByteArray? = null


    fun startCamera(initialFacing: Int) {
        currentFacing = initialFacing
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(facing: Int) {
        Log.d("CameraStreamer", "Switching camera to $facing")
        if (currentFacing != facing) {
            currentFacing = facing
            rebindCameraUseCases()
        }
    }

    private fun rebindCameraUseCases() {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            bindCameraUseCases()
        }
    }

    private fun bindCameraUseCases() {
        Log.d("CameraStreamer", "Binding camera use cases for facing $currentFacing")
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(currentFacing).build()

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalyzer())
            }

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            Log.d("CameraStreamer", "Camera use cases bound successfully")
        } catch (exc: Exception) {
            Log.e("CameraStreamer", "Use case binding failed", exc)
        }
    }

    fun stopCamera() {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            cameraProvider?.unbindAll()
        }
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(image: ImageProxy) {
            toJpeg(image)?.let { onFrame(it) }
            image.close()
        }
    }

    private fun toJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val nv21 = image.toNv21ByteArray()
        if (nv21 != null) {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
            return out.toByteArray()
        }
        return null
    }

    fun ImageProxy.toNv21ByteArray(): ByteArray? {
        if (format != android.graphics.ImageFormat.YUV_420_888) {
            return null
        }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val width = width
        val height = height

        val nv21ByteArray = ByteArray(width * height * 3 / 2)

        var nv21WriteIndex = 0
        val yRowStride = yPlane.rowStride

        if (yRowStride == width) {
            yBuffer.get(nv21ByteArray, 0, width * height)
            nv21WriteIndex += width * height
        } else {
            for (row in 0 until height) {
                yBuffer.get(nv21ByteArray, nv21WriteIndex, width)
                nv21WriteIndex += width
                if (row < height - 1) {
                    yBuffer.position(yBuffer.position() + yRowStride - width)
                }
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                nv21ByteArray[nv21WriteIndex++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21ByteArray[nv21WriteIndex++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }

        return nv21ByteArray
    }
}
