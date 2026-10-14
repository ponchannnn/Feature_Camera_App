package com.studentanalysis.zoomcapture.capability

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder
import com.studentanalysis.zoomcapture.zoom.LensModel

/** CameraCharacteristics などから端末の背面カメラの機能を調べ、CapabilityReport を作る */
object CapabilityProbe {

    fun probe(context: Context): CapabilityReport {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = chooseBackCamera(manager)
        val c = manager.getCameraCharacteristics(cameraId)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val map = requireNotNull(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))

        val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val isLogical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val ultraHighRes = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps

        val maxResMap = if (ultraHighRes) {
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        } else {
            null
        }
        val activeMaxRes = if (ultraHighRes) {
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.toRectI()
        } else {
            null
        }
        val aeRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        return CapabilityReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            cameraId = cameraId,
            hardwareLevel = hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            isLogicalMultiCamera = isLogical,
            physicalLenses = if (isLogical) physicalLenses(manager, c) else emptyList(),
            zoomRatioSupported = zoomRange != null && zoomRange.upper > zoomRange.lower,
            zoomRatioMin = zoomRange?.lower ?: 1f,
            zoomRatioMax = zoomRange?.upper ?: maxDigital,
            maxDigitalZoom = maxDigital,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            rawSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            ultraHighResolution = ultraHighRes && activeMaxRes != null,
            settingsOverrideZoom = settingsOverrideZoom(c),
            maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            aeCompensationMin = aeRange?.lower ?: 0,
            aeCompensationMax = aeRange?.upper ?: 0,
            aeCompensationStep = aeStep,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            timestampRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            activeArray = requireNotNull(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).toRectI(),
            activeArrayMaxResolution = activeMaxRes,
            jpegSizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { it.toSizeI() },
            rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().map { it.toSizeI() },
            maxResolutionJpegSizes = maxResMap?.getOutputSizes(ImageFormat.JPEG).orEmpty().map { it.toSizeI() },
            previewSizes = map.getOutputSizes(SurfaceHolder::class.java).orEmpty().map { it.toSizeI() },
            supportedExtensions = extensions(manager, cameraId),
        )
    }

    /** 背面の論理カメラ(配下のレンズが一番多いもの)を選ぶ。なければ最初の背面カメラ */
    private fun chooseBackCamera(manager: CameraManager): String {
        val back = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK
        }
        require(back.isNotEmpty()) { "背面カメラが見つかりません" }
        return back.maxByOrNull { manager.getCameraCharacteristics(it).physicalCameraIds.size } ?: back.first()
    }

    private fun physicalLenses(manager: CameraManager, logical: CameraCharacteristics): List<PhysicalLens> {
        val refFocal = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return emptyList()
        val refWidth = logical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return emptyList()
        return logical.physicalCameraIds.mapNotNull { id ->
            runCatching {
                val pc = manager.getCameraCharacteristics(id)
                val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
                val width = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!.width
                val pixels = pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
                PhysicalLens(
                    id = id,
                    focalLengthMm = focal,
                    sensorWidthMm = width,
                    pixelArray = pixels.toSizeI(),
                    equivalentZoom = LensModel.equivalentZoom(focal, width, refFocal, refWidth),
                )
            }.getOrNull()
        }.sortedBy { it.equivalentZoom }
    }

    private fun settingsOverrideZoom(c: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val overrides = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES) ?: return false
        return CameraMetadata.CONTROL_SETTINGS_OVERRIDE_ZOOM in overrides
    }

    private fun extensions(manager: CameraManager, cameraId: String): List<String> = runCatching {
        manager.getCameraExtensionCharacteristics(cameraId).supportedExtensions.map {
            when (it) {
                CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
                CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
                CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
                CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
                else -> "TYPE_$it"
            }
        }
    }.getOrDefault(emptyList())

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private fun Rect.toRectI() = RectI(left, top, right, bottom)

    private fun Size.toSizeI() = SizeI(width, height)
}
