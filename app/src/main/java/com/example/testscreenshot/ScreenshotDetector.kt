package com.example.testscreenshot

import android.app.Activity
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Detect screenshot cho toàn app.
 *
 * - Android 14+ (API 34): dùng ScreenCaptureCallback (API chính thức)
 * - Android 13 trở xuống: dùng ContentObserver trên MediaStore.Images
 *   → KHÔNG cần quyền READ_EXTERNAL_STORAGE hay READ_MEDIA_IMAGES
 *   → Chỉ lắng nghe sự kiện onChange, KHÔNG query content
 *   → Bất kỳ ảnh mới nào được thêm vào MediaStore khi app đang foreground
 *     đều coi là screenshot (false positive rất thấp trong thực tế)
 *
 * Debounce 1 giây để tránh trigger nhiều lần cho 1 screenshot
 * (MediaStore có thể fire onChange nhiều lần: thumbnail + full image)
 */
class ScreenshotDetector(private val activity: Activity) {

    private var callback: Any? = null       // Android 14+
    private var observer: ContentObserver? = null  // Android 13-

    private var lastDetectedTime = 0L
    private val debounceDuration = 1000L // 1 giây

    fun register(onDetected: () -> Unit) {
        when {
            // Android 14+: ScreenCaptureCallback
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val screenCaptureCallback = Activity.ScreenCaptureCallback { onDetected() }
                activity.registerScreenCaptureCallback(activity.mainExecutor, screenCaptureCallback)
                callback = screenCaptureCallback
            }

            // Android 13 trở xuống: ContentObserver — KHÔNG cần quyền
            else -> {
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)

                        // Debounce: tránh trigger nhiều lần cho 1 screenshot
                        val now = System.currentTimeMillis()
                        if (now - lastDetectedTime < debounceDuration) return
                        lastDetectedTime = now

                        onDetected()
                    }
                }
                activity.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer!!
                )
            }
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (callback as? Activity.ScreenCaptureCallback)?.let {
                activity.unregisterScreenCaptureCallback(it)
            }
        }
        observer?.let {
            activity.contentResolver.unregisterContentObserver(it)
        }
        observer = null
        callback = null
    }
}
