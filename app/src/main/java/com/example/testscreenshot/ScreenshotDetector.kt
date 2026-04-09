package com.example.testscreenshot

import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Detect screenshot bằng FileObserver (inotify) — không cần xin quyền.
 *
 * Nguyên lý:
 * - FileObserver sử dụng Linux inotify API để giám sát sự kiện file system.
 * - Khi user chụp screenshot, OS ghi file vào thư mục Screenshots.
 * - FileObserver nhận event CLOSE_WRITE (file đã ghi xong) → ta biết có screenshot mới.
 * - Không cần READ_EXTERNAL_STORAGE vì FileObserver chỉ nhận event + tên file,
 *   không đọc nội dung file.
 *
 * Dùng cho Android 12 (API 32) trở xuống.
 * Android 13+ nên dùng ScreenCaptureCallback hoặc Activity#registerScreenCaptureCallback.
 */
class ScreenshotDetector(
    private val onScreenshotDetected: (path: String) -> Unit
) {
    companion object {
        private const val TAG = "ScreenshotDetector"
    }

    private val observers = mutableListOf<FileObserver>()

    /**
     * Các thư mục screenshot phổ biến trên các dòng máy Android.
     * - Samsung, Pixel, AOSP: Pictures/Screenshots
     * - Một số OEM: DCIM/Screenshots
     * - Xiaomi: DCIM/Screenshots hoặc Pictures/Screenshots
     */
    private fun getScreenshotDirectories(): List<File> {
        val externalStorage = Environment.getExternalStorageDirectory()
        return listOf(
            File(externalStorage, "Pictures/Screenshots"),
            File(externalStorage, "DCIM/Screenshots"),
            File(externalStorage, "Screenshots"),
        ).filter { it.exists() || it.mkdirs().not().also { /* thư mục không tồn tại, bỏ qua */ } }
         .filter { it.exists() && it.isDirectory }
    }

    fun startDetecting() {
        stopDetecting()

        val dirs = getScreenshotDirectories()
        if (dirs.isEmpty()) {
            Log.w(TAG, "Không tìm thấy thư mục screenshot nào để giám sát")
            return
        }

        for (dir in dirs) {
            Log.d(TAG, "Bắt đầu giám sát: ${dir.absolutePath}")

            val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : dùng constructor nhận File
                object : FileObserver(dir, CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        handleEvent(dir, path)
                    }
                }
            } else {
                // Android 9 trở xuống: dùng constructor nhận String (deprecated nhưng cần thiết)
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        handleEvent(dir, path)
                    }
                }
            }

            observer.startWatching()
            observers.add(observer)
        }
    }

    fun stopDetecting() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun handleEvent(dir: File, path: String?) {
        if (path == null) return

        // Chỉ quan tâm file ảnh
        val lowerPath = path.lowercase()
        if (!lowerPath.endsWith(".png") &&
            !lowerPath.endsWith(".jpg") &&
            !lowerPath.endsWith(".jpeg") &&
            !lowerPath.endsWith(".webp")
        ) return

        val fullPath = File(dir, path).absolutePath
        Log.d(TAG, "Screenshot detected: $fullPath")
        onScreenshotDetected(fullPath)
    }
}
