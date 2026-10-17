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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * بيدمج الترجمة (SRT) جوه الفيديو ويصغّر الدقة لـ 720p.
 * لازم start() و cancel() يتنادوا من الـ Main Thread.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoBurner(private val context: Context) {

    companion object {
        // الضلع الأقصر في الفيديو الناتج (720 = 720p). مش بيكبّر فيديو أصغر منه.
        const val TARGET_SHORT_SIDE = 720

        // جودة الضغط: بت لكل بكسل لكل إطار. أعلى = جودة وحجم أكبر.
        // 0.065 تقريبًا 1.8 ميجابت/ث لفيديو 720p، يعني حوالي 300 ميجا لفيديو 22 دقيقة.
        // لو عايز حجم أقل نزّله لـ 0.05، ولو عايز جودة أعلى ارفعه لـ 0.08.
        const val BITS_PER_PIXEL = 0.065
        const val ASSUMED_FPS = 30L
    }

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputUri: Uri)
        fun onError(message: String)
    }

    private data class VideoInfo(val outWidth: Int, val outHeight: Int, val bitrate: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var callback: Callback? = null
    private var finished = true

    val isRunning: Boolean get() = !finished

    private val pollRunnable = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (finished) return
            val state = t.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                callback?.onProgress(progressHolder.progress)
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    fun start(videoUri: Uri, srtUri: Uri, callback: Callback) {
        if (!finished) return
        finished = false
        this.callback = callback
        cleanOldTempFiles()

        try {
            val cues = readCues(srtUri)
            if (cues.isEmpty()) {
                fail("ملف الترجمة فاضي أو مش بصيغة SRT سليمة")
                return
            }

            val info = readVideoInfo(videoUri)

            // الترتيب مهم: نصغّر الأول، وبعدين نرسم الترجمة بأبعاد الفيديو الناتج
            val presentation = Presentation.createForWidthAndHeight(
                info.outWidth,
                info.outHeight,
                Presentation.LAYOUT_SCALE_TO_FIT
            )
            val overlay = SubtitleOverlay(context, cues, info.outWidth, info.outHeight)
            val overlayEffect = OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))

            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
                .setEffects(
                    Effects(
                        listOf<AudioProcessor>(),
                        listOf<Effect>(presentation, overlayEffect)
                    )
                )
                .build()

            val outFile = File(context.cacheDir, "burn_${System.currentTimeMillis()}.mp4")
            tempFile = outFile

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(info.bitrate)
                        .build()
                )
                .setEnableFallback(true)
                .build()

            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        onExportCompleted(outFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        val detail = exportException.message ?: exportException.errorCodeName
                        fail("فشل الدمج: $detail")
                    }
                })
                .build()

            transformer = t
            callback.onProgress(0)
            t.start(editedItem, outFile.absolutePath)
            mainHandler.postDelayed(pollRunnable, 300)
        } catch (e: Exception) {
            fail(e.message ?: "حصل خطأ غير متوقع")
        }
    }

    fun cancel() {
        if (finished) return
        finished = true
        mainHandler.removeCallbacks(pollRunnable)
        transformer?.cancel()
        transformer = null
        tempFile?.delete()
        tempFile = null
    }

    // ---------------------------------------------------------------- //

    private fun readCues(srtUri: Uri): List<SubtitleCue> {
        val bytes = context.contentResolver.openInputStream(srtUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذر فتح ملف الترجمة")
        return SrtParser.parse(bytes)
    }

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

            // البت ريت محسوب من أبعاد الناتج، ومش بيعدّي 80% من البت ريت الأصلي
            val computed = (outW.toLong() * outH.toLong() * ASSUMED_FPS * BITS_PER_PIXEL).toLong()
            val original = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            val capped = if (original > 0) minOf(computed, (original * 0.8).toLong()) else computed
            val bitrate = capped.coerceIn(600_000L, 20_000_000L).toInt()

            return VideoInfo(outW, outH, bitrate)
        } finally {
            retriever.release()
        }
    }

    private fun makeEven(value: Int): Int = maxOf(2, (value / 2) * 2)

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
                    mainHandler.removeCallbacks(pollRunnable)
                    transformer = null
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
            if (it.name.startsWith("burn_") && it.name.endsWith(".mp4")) it.delete()
        }
    }

    private fun fail(message: String) {
        if (finished && callback == null) return
        finished = true
        mainHandler.removeCallbacks(pollRunnable)
        transformer = null
        tempFile?.delete()
        tempFile = null
        callback?.onError(message)
    }
}
