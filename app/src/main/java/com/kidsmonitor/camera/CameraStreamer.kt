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

    // Reusable buffers for performance
    private var nv21: ByteArray? = null
    private var uBytes: ByteArray? = null
    private var vBytes: ByteArray? = null


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
            toJpeg(image)?.let { onFrame(it) }
            image.close()
        }
    }

    private fun toJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21Size = image.width * image.height * 3 / 2
        if (nv21 == null || nv21!!.size != nv21Size) {
            nv21 = ByteArray(nv21Size)
        }
        val nv21 = this.nv21!!

        // Y plane copy
        val yRowStride = yPlane.rowStride
        if (yRowStride == image.width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var yIndex = 0
            for (y in 0 until image.height) {
                yBuffer.position(y * yRowStride)
                yBuffer.get(nv21, yIndex, image.width)
                yIndex += image.width
            }
        }

        // U and V planes copy
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        if (uBytes == null || uBytes!!.size != uSize) {
            uBytes = ByteArray(uSize)
        }
        val uBytes = this.uBytes!!
        uBuffer.get(uBytes)

        if (vBytes == null || vBytes!!.size != vSize) {
            vBytes = ByteArray(vSize)
        }
        val vBytes = this.vBytes!!
        vBuffer.get(vBytes)

        var vuIndex = image.width * image.height
        for (y in 0 until image.height / 2) {
            for (x in 0 until image.width / 2) {
                val uIndex = y * uRowStride + x * uPixelStride
                val vIndex = y * vRowStride + x * vPixelStride
                if (vuIndex < nv21.size - 1 && vIndex < vBytes.size && uIndex < uBytes.size) {
                    nv21[vuIndex++] = vBytes[vIndex]
                    nv21[vuIndex++] = uBytes[uIndex]
                }
            }
        }
        return nv21
    }
}
