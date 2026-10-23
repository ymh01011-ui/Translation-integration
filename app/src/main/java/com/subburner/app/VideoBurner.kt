package com.subburner.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * بيدمج الترجمة (SRT) جوه الفيديو باستخدام FFmpeg.
 * الصوت (أول مسار) بيتحوّل لـ AAC ستيريو لأعلى توافق.
 * الفيديو بيتصغّر لـ 720p ويتضغط بجودة ثابتة (CRF).
 * لازم start() و cancel() يتنادوا من الـ Main Thread.
 */
class VideoBurner(private val context: Context) {

    companion object {
        // الضلع الأقصر في الفيديو الناتج. مش بيكبّر فيديو أصغر منه.
        const val TARGET_SHORT_SIDE = 720

        // الجودة: رقم أقل = جودة أعلى وحجم أكبر. 20 جودة عالية جدًا، 24 حجم أصغر.
        const val CRF = 22

        // سرعة الضغط: veryfast أسرع، slow أبطأ وبيطلع حجم أصغر بنفس الجودة.
        const val PRESET = "veryfast"

        // شكل الترجمة (المقاييس نسبة لشاشة ارتفاعها 288 وحدة)
        const val FONT_SIZE = 18
        const val OUTLINE = 2
        const val MARGIN_V = 14

        // الصوت بيتحوّل لـ AAC ستيريو عشان يشتغل في تيليجرام وكل الأجهزة
        const val AUDIO_BITRATE = "192k"

        // اسم خط معيّن من فولدر assets/fonts (سيبه فاضي لاستخدام خط النظام)
        const val FONT_NAME = ""
    }

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputUri: Uri)
        fun onError(message: String)
    }

    private data class VideoInfo(val outWidth: Int, val outHeight: Int, val durationMs: Long)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: Callback? = null
    private var sessionId: Long = -1L
    private var tempFile: File? = null
    private var finished = true

    val isRunning: Boolean get() = !finished

    fun start(videoUri: Uri, srtUri: Uri, callback: Callback) {
        if (!finished) return
        finished = false
        this.callback = callback
        cleanOldTempFiles()

        try {
            // 1) الترجمة: نفك الترميز (UTF-8 أو Windows-1256) ونكتبها UTF-8 نظيفة
            val srtBytes = context.contentResolver.openInputStream(srtUri)?.use { it.readBytes() }
                ?: throw IllegalStateException("تعذر فتح ملف الترجمة")
            val srtText = SrtParser.decode(srtBytes)
            if (SrtParser.parseText(srtText).isEmpty()) {
                fail("ملف الترجمة فاضي أو مش بصيغة SRT سليمة")
                return
            }
            val srtFile = File(context.cacheDir, "burn_sub.srt")
            srtFile.writeText(srtText, Charsets.UTF_8)

            // 2) معلومات الفيديو
            val info = readVideoInfo(videoUri)

            // 3) الخطوط (لازم لعرض العربي)
            FFmpegKitConfig.setFontDirectoryList(context, prepareFontDirs(), null)

            val outFile = File(context.cacheDir, "burn_${System.currentTimeMillis()}.mp4")
            tempFile = outFile

            val inputPath = FFmpegKitConfig.getSafParameterForRead(context, videoUri)

            var style = "FontSize=$FONT_SIZE,Outline=$OUTLINE,Shadow=0,MarginV=$MARGIN_V"
            if (FONT_NAME.isNotBlank()) style += ",FontName=$FONT_NAME"

            val filter = "scale=${info.outWidth}:${info.outHeight}:flags=lanczos," +
                "subtitles=${srtFile.absolutePath}:force_style='$style'"

            // الصوت: أول مسار صوت بس، يتحوّل لـ AAC ستيريو (أعلى توافق مع تيليجرام والأجهزة)
            val audioArgs = listOf("-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ac", "2")

            val args = ArrayList<String>()
            args.addAll(
                listOf(
                    "-y",
                    "-i", inputPath,
                    "-map", "0:v:0",
                    "-map", "0:a:0?",
                    "-sn", "-dn",
                    "-vf", filter,
                    "-c:v", "libx264",
                    "-preset", PRESET,
                    "-crf", CRF.toString(),
                    "-pix_fmt", "yuv420p"
                )
            )
            args.addAll(audioArgs)
            args.addAll(listOf("-movflags", "+faststart", outFile.absolutePath))

            callback.onProgress(0)

            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s ->
                    mainHandler.post {
                        if (finished) return@post
                        val rc = s.returnCode
                        when {
                            ReturnCode.isSuccess(rc) -> onExportCompleted(outFile)
                            ReturnCode.isCancel(rc) -> { /* الإلغاء بيتعامل معاه cancel() */ }
                            else -> {
                                val logs = s.allLogsAsString ?: ""
                                fail("فشل الدمج:\n" + logs.takeLast(900))
                            }
                        }
                    }
                },
                null,
                { stats ->
                    val total = info.durationMs
                    if (total > 0) {
                        val pct = ((stats.time.toLong() * 100) / total).toInt().coerceIn(0, 99)
                        mainHandler.post { if (!finished) callback.onProgress(pct) }
                    }
                }
            )
            sessionId = session.sessionId
        } catch (e: Throwable) {
            // Throwable مش Exception: عشان نمسك أخطاء تحميل مكتبة FFmpeg بدل ما التطبيق يقفل
            val cause = e.cause?.let { "\nالسبب: " + it.javaClass.simpleName + ": " + it.message } ?: ""
            fail(
                "تعذر تشغيل FFmpeg\n" +
                    e.javaClass.simpleName + ": " + (e.message ?: "") + cause +
                    "\nمعالج الجهاز: " + Build.SUPPORTED_ABIS.joinToString()
            )
        }
    }

    fun cancel() {
        if (finished) return
        finished = true
        if (sessionId >= 0) FFmpegKit.cancel(sessionId)
        sessionId = -1L
        tempFile?.delete()
        tempFile = null
    }

    // ---------------------------------------------------------------- //

    private fun readVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            var w = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var h = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (w <= 0 || h <= 0) throw IllegalStateException("تعذر قراءة أبعاد الفيديو")

            // الفيديو الطولي بيتخزن أحيانًا بأبعاد مقلوبة مع علامة تدوير
            if (rotation == 90 || rotation == 270) {
                val tmp = w; w = h; h = tmp
            }

            // تصغير الضلع الأقصر لـ 720 (من غير تكبير) مع الحفاظ على النسبة
            val shortSide = minOf(w, h)
            val target = minOf(TARGET_SHORT_SIDE, shortSide)
            val scale = target.toDouble() / shortSide.toDouble()
            val outW = makeEven(Math.round(w * scale).toInt())
            val outH = makeEven(Math.round(h * scale).toInt())

            return VideoInfo(outW, outH, duration)
        } finally {
            retriever.release()
        }
    }

    private fun makeEven(value: Int): Int = maxOf(2, (value / 2) * 2)

    /** فولدرات الخطوط: خطوط النظام + أي خط حاطه في assets/fonts */
    private fun prepareFontDirs(): List<String> {
        val dirs = arrayListOf("/system/fonts")
        try {
            val names = context.assets.list("fonts") ?: emptyArray()
            if (names.isNotEmpty()) {
                val dir = File(context.filesDir, "fonts")
                dir.mkdirs()
                for (name in names) {
                    val f = File(dir, name)
                    if (!f.exists()) {
                        context.assets.open("fonts/$name").use { input ->
                            f.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                dirs.add(dir.absolutePath)
            }
        } catch (e: Exception) {
            // نكمل بخطوط النظام
        }
        return dirs
    }

    private fun onExportCompleted(file: File) {
        callback?.onProgress(100)
        // النسخ لمعرض الفيديو في Thread منفصل عشان الواجهة متتجمدش
        Thread {
            try {
                val name = "subtitled_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val uri = saveToGallery(file, name)
                file.delete()
                mainHandler.post {
                    if (finished) return@post
                    finished = true
                    sessionId = -1L
                    tempFile = null
                    callback?.onSuccess(uri)
                }
            } catch (e: Exception) {
                mainHandler.post { fail("اتدمج الفيديو لكن فشل الحفظ: ${e.message}") }
            }
        }.start()
    }

    private fun saveToGallery(file: File, name: String): Uri {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/SubtitleBurner"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء ملف في المعرض")

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("تعذر الكتابة في المعرض")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "SubtitleBurner"
            )
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(
                context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null
            )
            return Uri.fromFile(dest)
        }
    }

    private fun cleanOldTempFiles() {
        context.cacheDir.listFiles()?.forEach {
            if ((it.name.startsWith("burn_") && it.name.endsWith(".mp4")) || it.name == "burn_sub.srt") {
                it.delete()
            }
        }
    }

    private fun fail(message: String) {
        if (finished && callback == null) return
        finished = true
        sessionId = -1L
        tempFile?.delete()
        tempFile = null
        callback?.onError(message)
    }
}
