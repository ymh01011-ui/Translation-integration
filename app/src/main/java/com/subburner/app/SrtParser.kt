package com.subburner.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SrtParser {

    private val TIME_REGEX = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val INDEX_REGEX = Regex("""^\d+$""")
    private val HTML_TAG_REGEX = Regex("""<[^>]*>""")
    private val ASS_TAG_REGEX = Regex("""\{[^}]*\}""")

    /** نقطة الدخول: تاخد بايتات الملف وترجع قايمة الترجمات مرتبة بالوقت. */
    fun parse(bytes: ByteArray): List<SubtitleCue> = parseText(decode(bytes))

    /** فك الترميز: UTF-16 (لو فيه BOM) ثم UTF-8 الصارم، وإلا Windows-1256 للعربي القديم. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1256"))
        }
        return text.removePrefix("\uFEFF")
    }

    fun parseText(raw: String): List<SubtitleCue> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()

        var start = 0L
        var end = 0L
        var buffer = ArrayList<String>()
        var inCue = false

        fun flush() {
            if (inCue) {
                val text = clean(buffer.joinToString("\n"))
                if (text.isNotBlank() && end > start) {
                    cues.add(SubtitleCue(start, end, text))
                }
            }
            buffer = ArrayList()
            inCue = false
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val match = TIME_REGEX.find(trimmed)

            if (match != null) {
                flush()
                val g = match.groupValues
                start = toMs(g[1], g[2], g[3], g[4])
                end = toMs(g[5], g[6], g[7], g[8])
                inCue = true
            } else if (inCue) {
                if (trimmed.isEmpty()) {
                    flush()
                } else if (INDEX_REGEX.matches(trimmed) && nextNonEmptyIsTime(lines, i + 1)) {
                    // رقم تسلسلي جاي قبل توقيت من غير سطر فاضي: ننهي الترجمة الحالية
                    flush()
                } else {
                    buffer.add(line.trim())
                }
            }
            i++
        }
        flush()

        return cues.sortedBy { it.startMs }
    }

    private fun nextNonEmptyIsTime(lines: List<String>, from: Int): Boolean {
        var j = from
        while (j < lines.size) {
            val t = lines[j].trim()
            if (t.isNotEmpty()) return TIME_REGEX.containsMatchIn(t)
            j++
        }
        return false
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        // "5" تعني 500 ملي ثانية، و"50" تعني 500 كمان
        val millis = ms.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + millis
    }

    private fun clean(text: String): String =
        text.replace(HTML_TAG_REGEX, "")
            .replace(ASS_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}
