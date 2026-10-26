package com.speedtracker.core.ml

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions

class PoseAnalyzer(
    private val onPoseDetected: (pose: Pose, frameWidth: Int, frameHeight: Int) -> Unit
) : ImageAnalysis.Analyzer {

    // Usar el detector preciso para análisis biomecánico confiable
    private val options = AccuratePoseDetectorOptions.Builder()
        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
        .build()

    private val poseDetector = PoseDetection.getClient(options)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val isRotated = rotation == 90 || rotation == 270
            val frameWidth = if (isRotated) imageProxy.height else imageProxy.width
            val frameHeight = if (isRotated) imageProxy.width else imageProxy.height

            val image = InputImage.fromMediaImage(mediaImage, rotation)

            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    onPoseDetected(pose, frameWidth, frameHeight)
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
