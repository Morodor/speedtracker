package com.speedtracker.core.ml

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.speedtracker.core.math.VBTAnalyzer

class PoseAnalyzer(
    private val onPoseDetected: (pose: Pose?, frameBitmap: Bitmap?, frameWidth: Int, frameHeight: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val options = AccuratePoseDetectorOptions.Builder()
        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
        .build()

    private val poseDetector = PoseDetection.getClient(options)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val rawBmp = imageProxy.toBitmap()
            val uprightBmp = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
            } else {
                rawBmp
            }

            val frameWidth = uprightBmp.width
            val frameHeight = uprightBmp.height

            // Si el disco de 45 cm ya está calibrado, ejecutamos tracking óptico directo de alta velocidad (30+ FPS)
            if (VBTAnalyzer.calibratedPlateCenter != null && (VBTAnalyzer.calibratedPlateRadius ?: 0f) > 8f) {
                onPoseDetected(null, uprightBmp, frameWidth, frameHeight)
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                poseDetector.process(image)
                    .addOnSuccessListener { pose ->
                        onPoseDetected(pose, uprightBmp, frameWidth, frameHeight)
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                onPoseDetected(null, uprightBmp, frameWidth, frameHeight)
                imageProxy.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            imageProxy.close()
        }
    }
}
