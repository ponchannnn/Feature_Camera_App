package com.studentanalysis.zoomcapture.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

data class DeviceStatus(
    val batteryPercent: Int?,
    val charging: Boolean,
    val thermalStatus: Int,
    val freeBytes: Long,
) {
    val thermalSevere: Boolean get() = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
}

/** バッテリー・端末温度・空き容量の監視 */
class DeviceMonitor(private val context: Context, private val storageDir: File) {

    private val power = context.getSystemService(PowerManager::class.java)

    fun current(): DeviceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return DeviceStatus(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            thermalStatus = power.currentThermalStatus,
            freeBytes = runCatching { StatFs(storageDir.path).availableBytes }.getOrDefault(0L),
        )
    }

    fun statusFlow(intervalMs: Long = 5_000): Flow<DeviceStatus> = flow {
        while (true) {
            emit(current())
            delay(intervalMs)
        }
    }

    companion object {
        fun thermalLabel(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "やや高い"
            PowerManager.THERMAL_STATUS_MODERATE -> "高い"
            PowerManager.THERMAL_STATUS_SEVERE -> "非常に高い"
            PowerManager.THERMAL_STATUS_CRITICAL -> "危険"
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "緊急"
            else -> "不明"
        }
    }
}
