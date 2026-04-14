package com.example.testscreenshot

import android.app.Activity
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

class ScreenshotDetector(private val activity: Activity) {

    private var callback: Any? = null // Android 14+
    private var contentObserver: ContentObserver? = null // Dự phòng cho Android 13-
    private val fileObservers = mutableListOf<FileObserver>() // Chính cho Android 13-

    private var lastDetectedTime = 0L
    private val debounceDuration = 1000L

    fun register(onDetected: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: API chính thức, không bao giờ nhận nhầm ảnh download
            val screenCaptureCallback = Activity.ScreenCaptureCallback {
                onDetected()
            }
            activity.registerScreenCaptureCallback(activity.mainExecutor, screenCaptureCallback)
            callback = screenCaptureCallback
        } else {
            // Android 13 trở xuống: Kết hợp FileObserver và ContentObserver
            setupFileObservers(onDetected)
            setupContentObserver(onDetected)
        }
    }

    private fun setupFileObservers(onDetected: () -> Unit) {
        val screenshotDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots")
        )

        for (dir in screenshotDirs) {
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists()) {
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, CLOSE_WRITE) {
                        override fun onEvent(event: Int, path: String?) {
                            triggerDetection(onDetected)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(dir.absolutePath, CLOSE_WRITE) {
                        override fun onEvent(event: Int, path: String?) {
                            triggerDetection(onDetected)
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            }
        }
    }

    private fun setupContentObserver(onDetected: () -> Unit) {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val uriString = uri?.toString()?.lowercase() ?: ""
                
                // ContentObserver trên MediaStore rất dễ bị trigger bởi ảnh download.
                // Ta chỉ lọc những URI có khả năng là screenshot (một số OEM có nhúng "screenshot" vào URI)
                // Hoặc nếu FileObserver đã chạy thì nó sẽ là nguồn tin cậy hơn.
                if (uriString.contains("screenshot")) {
                    triggerDetection(onDetected)
                }
            }
        }
        activity.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
    }

    private fun triggerDetection(onDetected: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastDetectedTime < debounceDuration) return
        lastDetectedTime = now

        activity.runOnUiThread {
            onDetected()
        }
    }

    fun unregister() {
        // Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (callback as? Activity.ScreenCaptureCallback)?.let {
                activity.unregisterScreenCaptureCallback(it)
            }
        }
        
        // Android 13-
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        
        contentObserver?.let {
            activity.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
    }
}
