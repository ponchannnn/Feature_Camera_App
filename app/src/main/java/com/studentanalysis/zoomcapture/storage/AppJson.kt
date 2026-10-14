package com.studentanalysis.zoomcapture.storage

import kotlinx.serialization.json.Json

val AppJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** ファイル名に使えない文字を置き換える */
fun sanitizeFileName(name: String): String =
    name.trim().replace(Regex("""[\\/:*?"<>|\s]+"""), "_").ifEmpty { "untitled" }
