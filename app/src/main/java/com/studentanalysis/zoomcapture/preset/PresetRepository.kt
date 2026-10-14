package com.studentanalysis.zoomcapture.preset

import android.content.Context
import androidx.core.content.edit
import com.studentanalysis.zoomcapture.storage.AppJson
import com.studentanalysis.zoomcapture.storage.sanitizeFileName
import java.io.File

/** プリセットセットを、アプリ内部の領域に JSON で保存・読み込みする */
class PresetRepository(context: Context) {

    private val dir = File(context.filesDir, "preset_sets").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun loadAll(): List<PresetSet> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { AppJson.decodeFromString<PresetSet>(f.readText()) }.getOrNull() }
            .sortedBy { it.name }

    fun save(set: PresetSet) {
        val target = fileFor(set.name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(PresetSet.serializer(), set))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(name: String) {
        fileFor(name).delete()
    }

    var selectedName: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) {
            prefs.edit { putString(KEY_SELECTED, value) }
        }

    private fun fileFor(name: String) = File(dir, sanitizeFileName(name) + ".json")

    private companion object {
        const val KEY_SELECTED = "selected_preset_set"
    }
}
