package com.studentanalysis.zoomcapture.ui.common

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.ui.CameraState

/**
 * カメラのプレビュー(SurfaceView)。Surface が指定サイズで用意できたら onSurface を呼び、破棄されたら null で呼ぶ。
 * overlay はプレビューと同じ大きさで重ねて表示する。
 */
@Composable
fun CameraPreview(
    previewSize: SizeI,
    displayAspect: Float,
    cameraState: CameraState,
    onSurface: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val callback by rememberUpdatedState(onSurface)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(displayAspect).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.setFixedSize(previewSize.width, previewSize.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                if (width == previewSize.width && height == previewSize.height) callback(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) = callback(null)
                        })
                    }
                },
            )
            overlay()
            val status = when (cameraState) {
                CameraState.Opening -> "カメラを起動しています…"
                is CameraState.Error -> "カメラのエラー: ${cameraState.message}"
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)),
                    color = if (cameraState is CameraState.Error) MaterialTheme.colorScheme.error else Color.White,
                )
            }
        }
    }
}
