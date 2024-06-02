package test.videocall.ui

import android.Manifest
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.rtc.media.player.VideoPlayer
import desidev.rtc.rtcmsg.RTCMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import test.videocall.permissionGrantState


@Composable
fun CameraToVideoPlayer() {
    val context = LocalContext.current
    val cameraCapture = remember { CameraCaptureImpl(context) }
    var format by remember { mutableStateOf<MediaFormat?>(null) }
    var samples by remember { mutableStateOf<ReceiveChannel<Pair<ByteArray, BufferInfo>>?>(null) }

    val scope = rememberCoroutineScope()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isCapturing by cameraCapture.isCaptureRunning.collectAsState()


    DisposableEffect(key1 = Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("CameraToVideoPlayer", "ON_RESUME")
                    scope.launch {
                        cameraCapture.openCamera()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("CameraToVideoPlayer", "ON_PAUSE")
                    scope.launch {
                        cameraCapture.closeCamera()
                    }
                }

                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    suspend fun start() {
        cameraCapture.startCapture()
        format = cameraCapture.getMediaFormat().await()
        samples = cameraCapture.compressChannel()
        Log.d("CameraToVideoPlayer", "start")
    }

    suspend fun stop() {
        cameraCapture.stopCapture()
        Log.d("CameraToVideoPlayer", "stop")
    }

    suspend fun toggleCamera() {
        val deviceInfo =
            cameraCapture.getCameras().first { it != cameraCapture.selectedCamera.value }
        cameraCapture.switchCamera(deviceInfo)

        if (cameraCapture.isCaptureRunning.value) {
            format = cameraCapture.getMediaFormat().await()
            samples = cameraCapture.compressChannel()
        }
    }

    Scaffold { systemPadding ->
        val (grantState, permRequester) = permissionGrantState(permissions = arrayOf(Manifest.permission.CAMERA))

        if (grantState.value.isAllGranted) {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(systemPadding)
            ) {
                val halfHeight = (maxHeight.value / 2).dp
                if (isCapturing && format != null && samples != null) {
                    VideoPlayerView(
                        format = format!!,
                        samples = samples!!.receiveAsFlow().map {
                            it.run {
                                RTCMessage.Sample(
                                    ptsUs = second.presentationTimeUs,
                                    buffer = first,
                                    flags = second.flags
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(halfHeight)
                            .align(Alignment.TopCenter)
                    )
                }

                // preview
                cameraCapture.PreviewView(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(halfHeight)
                        .fillMaxWidth()
                )

                Button(
                    onClick = {
                        scope.launch {
                            if (!isCapturing) {
                                start()
                            } else {
                                stop()
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Text(text = if (isCapturing) "Stop" else "Start")
                }

                FilledIconButton(
                    onClick = {
                        scope.launch { toggleCamera() }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                }
            }

        } else {
            LaunchedEffect(key1 = Unit) {
                permRequester.requestDeniedPermissions()
            }
        }
    }
}


@Composable
fun VideoPlayerView(
    format: MediaFormat, samples: Flow<RTCMessage.Sample>, modifier: Modifier = Modifier
) {
    val videoPlayer = remember(format) {
        VideoPlayer(format).also {
            Log.d("VideoPlayerView", "format: $format")
        }
    }

    val scope = rememberCoroutineScope()

    DisposableEffect(format) {
        videoPlayer.play()
        scope.launch(Dispatchers.Default) {
            samples.collect { sample ->
                videoPlayer.inputData(buffer = sample.buffer, info = BufferInfo().apply {
                    size = sample.buffer.size
                    presentationTimeUs = sample.ptsUs
                    flags = sample.flags
                })
            }
        }
        onDispose {
            scope.launch {
                videoPlayer.stop()
            }
        }
    }

    videoPlayer.VideoPlayerView(modifier = modifier)
}
