package com.studentanalysis.zoomcapture.storage

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 撮影した1ファイル分のデータ */
class CapturedFile(
    /** ファイル名の末尾に付ける文字列(連写なら "_b01" など) */
    val suffix: String,
    val extension: String,
    val bytes: ByteArray,
)

/** JPEG / DNG の保存と、Exif への撮影時刻の書き込み */
class ImageSaver {

    fun save(dir: File, baseName: String, file: CapturedFile, wallTimeMs: Long, presetId: String): File {
        val out = File(dir, "$baseName${file.suffix}.${file.extension}")
        out.outputStream().use { it.write(file.bytes) }
        if (file.extension == "jpg") writeExif(out, wallTimeMs, presetId)
        return out
    }

    private fun writeExif(file: File, wallTimeMs: Long, presetId: String) {
        runCatching {
            val date = Date(wallTimeMs)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(date))
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(wallTimeMs % 1000))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, SimpleDateFormat("XXX", Locale.ROOT).format(date))
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, presetId)
                saveAttributes()
            }
        }
    }
}
