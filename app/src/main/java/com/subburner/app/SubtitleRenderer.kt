package com.subburner.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

/**
 * بيرسم نص الترجمة (عربي أو غيره) على Bitmap شفاف بنفس أبعاد الفيديو.
 * الرسم بـ StaticLayout بيعمل توصيل الحروف واتجاه RTL تلقائي.
 */
class SubtitleRenderer(
    context: Context,
    private val width: Int,
    private val height: Int
) {

    companion object {
        // تقدر تعدّل القيم دي لتغيير شكل الترجمة
        const val TEXT_SIZE_RATIO = 0.055f      // حجم الخط نسبة لأصغر بُعد في الفيديو
        const val MAX_WIDTH_RATIO = 0.90f       // أقصى عرض للنص نسبة لعرض الفيديو
        const val BOTTOM_MARGIN_RATIO = 0.06f   // المسافة من أسفل الفيديو
        const val OUTLINE_RATIO = 0.12f         // سمك الحدود نسبة لحجم الخط
        const val FONT_ASSET_PATH = "fonts/arabic.ttf"
    }

    private val textSizePx = minOf(width, height) * TEXT_SIZE_RATIO
    private val maxTextWidth = (width * MAX_WIDTH_RATIO).toInt()
    private val bottomMarginPx = height * BOTTOM_MARGIN_RATIO

    private val typeface: Typeface = loadTypeface(context)

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
        typeface = this@SubtitleRenderer.typeface
        style = Paint.Style.FILL
    }

    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textSizePx
        typeface = this@SubtitleRenderer.typeface
        style = Paint.Style.STROKE
        strokeWidth = textSizePx * OUTLINE_RATIO * 2f
        strokeJoin = Paint.Join.ROUND
    }

    private fun loadTypeface(context: Context): Typeface =
        try {
            Typeface.createFromAsset(context.assets, FONT_ASSET_PATH)
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }

    private fun buildLayout(text: String, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.ANYRTL_LTR)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /** يرجّع Bitmap شفاف بحجم الفيديو والنص مرسوم أسفله. */
    fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokeLayout = buildLayout(text, strokePaint)
        val fillLayout = buildLayout(text, fillPaint)

        val x = (width - maxTextWidth) / 2f
        val y = (height - bottomMarginPx - fillLayout.height).coerceAtLeast(0f)

        canvas.save()
        canvas.translate(x, y)
        strokeLayout.draw(canvas)   // الحدود الأول
        fillLayout.draw(canvas)     // بعدين النص الأبيض فوقها
        canvas.restore()

        return bitmap
    }
}

/**
 * Overlay بيتغير مع الوقت: بيرجّع صورة الترجمة المناسبة لكل إطار.
 * أبعاد الـ Bitmap = أبعاد الفيديو بالظبط، عشان تتطابق مع الإطار 1:1.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SubtitleOverlay(
    context: Context,
    private val cues: List<SubtitleCue>,
    width: Int,
    height: Int
) : BitmapOverlay() {

    private val renderer = SubtitleRenderer(context, width, height)
    private val emptyBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private var lastIndex = -2
    private var lastBitmap: Bitmap = emptyBitmap

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000L
        val index = findCueIndex(timeMs)
        if (index != lastIndex) {
            lastIndex = index
            lastBitmap = if (index < 0) emptyBitmap else renderer.render(cues[index].text)
        }
        return lastBitmap
    }

    /** بحث ثنائي عن آخر ترجمة بدأت قبل الوقت ده، ويتأكد إنها لسه شغالة. */
    private fun findCueIndex(timeMs: Long): Int {
        var low = 0
        var high = cues.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= timeMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // نرجع كام ترجمة لورا للتعامل مع الترجمات المتداخلة
        var i = candidate
        var steps = 0
        while (i >= 0 && steps < 6) {
            if (timeMs < cues[i].endMs) return i
            i--
            steps++
        }
        return -1
    }
}
