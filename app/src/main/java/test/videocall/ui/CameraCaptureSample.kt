package test.videocall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import desidev.rtc.media.camera.CameraCaptureImpl
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.MediaMuxerWrapper
import test.videocall.R
import java.nio.ByteBuffer

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun CameraCaptureSample(modifier: Modifier = Modifier) {
    val TAG = "CameraCaptureSample"
    val context = LocalContext.current
    val cameraCapture = remember { CameraCaptureImpl(context) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startRecording() {
        scope.launch {
            cameraCapture.openCamera()
            cameraCapture.startCapture()
            val channel = cameraCapture.compressChannel()
            var muxer: MediaMuxerWrapper? = null
            try {
                muxer = MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/test.mp4")
                val trackId = muxer.addTrack(cameraCapture.getMediaFormat().await())
                muxer.start()

                withContext(Dispatchers.Default) {
                    while (!channel.isClosedForReceive) {
                        try {
                            val data = channel.receive()
                            muxer.writeSampleData(trackId, ByteBuffer.wrap(data.first), data.second)
                        } catch (ex: Exception) {
                            Log.i(TAG, "Error: ${ex.message}")
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.d(TAG, "Error: ${ex.message}")
            } finally {
                muxer?.stop()
            }
        }
    }

    fun stopRecording() {
        scope.launch {
            cameraCapture.closeCamera()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                cameraCapture.release()
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
        }

    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        LaunchedEffect(Unit) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box {
            cameraCapture.PreviewView(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(0.40f)
                    .aspectRatio(9 / 16f)
                    .border(1.dp, Color.Black)
                    .align(Alignment.BottomEnd)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = {
                    isRunning = !isRunning
                    if (isRunning) {
                        startRecording()
                    } else {
                        stopRecording()
                    }
                }) {
                    Text(if (isRunning) "Stop" else "Start")
                }

                IconButton(onClick = {
                    scope.launch {
                        cameraCapture.apply {
                            selectCamera(getCameras().first { it != selectedCamera.value })
                        }
                    }
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_cameraswitch_24),
                        contentDescription = null
                    )
                }
            }
        }
    }
}




