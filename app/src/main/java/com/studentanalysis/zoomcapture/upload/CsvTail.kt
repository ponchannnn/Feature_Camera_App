package com.studentanalysis.zoomcapture.upload

import java.io.File
import java.io.RandomAccessFile

/** captures.csv の、まだ送っていない部分のうち改行で終わっている行のまとまり */
class CsvChunk(val offset: Long, val bytes: ByteArray, val lines: List<String>) {
    val end: Long get() = offset + bytes.size
}

/** 書き込み中の captures.csv を、行の途中で切らずに読む */
object CsvTail {

    /** offset から、改行で終わっている行を maxLines 行・maxBytes バイトまで読む。読める行がなければ null */
    fun read(file: File, offset: Long, maxLines: Int, maxBytes: Int): CsvChunk? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            val available = raf.length() - offset
            if (available <= 0) return null
            val buf = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            raf.seek(offset)
            raf.readFully(buf)

            var end = 0
            var count = 0
            for (i in buf.indices) {
                if (buf[i] == '\n'.code.toByte()) {
                    end = i + 1
                    if (++count >= maxLines) break
                }
            }
            if (end == 0) return null
            val bytes = buf.copyOf(end)
            val lines = String(bytes, Charsets.UTF_8).split('\n').dropLast(1).map { it.trimEnd('\r') }
            return CsvChunk(offset, bytes, lines)
        }
    }

    fun firstLine(file: File): String? =
        if (!file.exists()) null else file.bufferedReader(Charsets.UTF_8).use { it.readLine() }

    /** 先頭から upTo バイト目までの改行の数 */
    fun countNewlines(file: File, upTo: Long = Long.MAX_VALUE): Long {
        if (!file.exists()) return 0
        var count = 0L
        var remaining = minOf(upTo, file.length())
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) count++
                remaining -= n
            }
        }
        return count
    }

    /** CSV の1行を列に分ける(captures.csv の書き出し方に合わせ、" で囲んだ列と "" に対応) */
    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
