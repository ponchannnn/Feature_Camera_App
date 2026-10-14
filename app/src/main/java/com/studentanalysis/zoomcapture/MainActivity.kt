package com.studentanalysis.zoomcapture

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.capture.CaptureScreen
import com.studentanalysis.zoomcapture.ui.common.ZoomCaptureTheme
import com.studentanalysis.zoomcapture.ui.deviceinfo.DeviceInfoScreen
import com.studentanalysis.zoomcapture.ui.home.HomeScreen
import com.studentanalysis.zoomcapture.ui.preseteditor.PresetEditorScreen
import com.studentanalysis.zoomcapture.ui.sessions.SessionsScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateRotation()
        setContent {
            ZoomCaptureTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate(onGranted = vm::onPermissionGranted) { AppRoot(vm) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRotation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    private fun updateRotation() {
        vm.displayRotationDegrees.value = when (display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val screen by vm.screen.collectAsState()
    val cap by vm.capability.collectAsState()
    val error by vm.capabilityError.collectAsState()

    when {
        error != null -> Message("カメラの情報を取得できませんでした: $error")
        cap == null -> Message("カメラの情報を調べています…")
        else -> when (screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.EDITOR -> PresetEditorScreen(vm)
            Screen.CAPTURE -> CaptureScreen(vm)
            Screen.SESSIONS -> SessionsScreen(vm)
            Screen.DEVICE_INFO -> DeviceInfoScreen(vm)
        }
    }
}

@Composable
private fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) onGranted() else launcher.launch(Manifest.permission.CAMERA)
    }
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("撮影にはカメラの権限が必要です")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("権限を許可する") }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { Text(text) }
}
