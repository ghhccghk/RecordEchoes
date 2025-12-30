package com.ghhccghk.musicplay.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object SmartImageCache {
    private lateinit var cacheDir: File
    private var maxCacheSize: Long = 50L * 1024 * 1024 // 默认50MB
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun init(context: Context, dirName: String = "cache/smart_image_cache", maxSize: Long = maxCacheSize) {
        cacheDir = File(context.getExternalFilesDir(null), dirName).apply { mkdirs() }
        maxCacheSize = maxSize
    }

    fun hasCache(url: String, customHash: String? = null): Boolean {
        val fileName = (customHash ?: url).md5()
        val file = File(cacheDir, fileName)
        return file.exists()
    }

    fun getCachedUri(url: String, customHash: String? = null): Uri? {
        return try {
            val fileName = (customHash ?: url).md5()
            val file = File(cacheDir, fileName)
//            Log.d("SmartImageCache", "request success 获取到缓存 : $file")
            if (file.exists()) Uri.fromFile(file) else null
        } catch (e: Exception) {
//            Log.e("SmartImageCache", "getCa     chedUri failed", e)
            null
        }
    }


    suspend fun getOrDownload(url: String, customHash: String? = null): Uri? {
        // 打印调用栈
//        val stackTrace = Thread.currentThread().stackTrace
//        val builder = StringBuilder("Full Call Stack:\n")
//        for (element in stackTrace) {
//            builder.append("    at ${element.className}.${element.methodName}")
//                .append(" (${element.fileName}:${element.lineNumber})\n")
//        }
//        Log.d("SmartImageCache", builder.toString())

        if (url.isBlank() || url == "") {
            Log.w("SmartImageCache", "无效 URL：$url")
            return null
        }

        val fileName = (customHash ?: url).md5()
        val file = File(cacheDir, fileName)

        if (!file.exists() || file.length() == 0L) {
            file.delete()
        }

        if (file.exists() && file.length() != 0L) {
            file.setLastModified(System.currentTimeMillis())
            Log.d("SmartImageCache", "获取到缓存 : $file")
            return Uri.fromFile(file)
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        file.setLastModified(System.currentTimeMillis())
                        trimCache()
                        Log.d("SmartImageCache", "request success 获取到缓存 : $file")
                        return@withContext Uri.fromFile(file)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e("SmartImageCache", "下载失败: $e url: $url")
                null
            }
        }

    }


    fun clearAll() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    private fun trimCache() {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheSize) return

        files.sortedBy { it.lastModified() }.forEach {
            if (it.delete()) total -= it.length()
            if (total <= maxCacheSize) return
        }
    }

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
