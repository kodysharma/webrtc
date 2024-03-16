package test.videocall.ui

import android.graphics.Bitmap
import android.media.Image
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.MediaMuxerWrapper
import test.videocall.R
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

@Composable
fun CameraCaptureSample(modifier: Modifier = Modifier) {
    val TAG = "CameraCaptureSample"
    val context = LocalContext.current
    val cameraCapture = remember { desidev.rtc.media.camera.CameraCapture.create(context) }
    val yuvToRgbConverter = remember { desidev.utility.yuv.YuvToRgbConverter(context) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun Image.toBitmap(): Bitmap {
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        yuvToRgbConverter.yuvToRgb(this, outputBitmap)
        return outputBitmap
    }

    fun startRecording() {
        scope.launch {
            cameraCapture.start()
            val channel = cameraCapture.compressedDataChannel()
            var muxer: MediaMuxerWrapper? = null
            try {
                muxer = MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/test.mp4")
                val trackId = muxer.addTrack(cameraCapture.getMediaFormat().get())
                muxer.start()
                withContext(Dispatchers.Default) {
                    while (channel.isOpenForReceive) {
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
            cameraCapture.stop()
        }
    }

    DisposableEffect(Unit) {
        println("adding preview frame listener")
        cameraCapture.setPreviewFrameListener { image: Image ->
            Log.d(TAG, "frame update")
            currentFrame = image.toBitmap()
            image.close()
        }
        onDispose {
            cameraCapture.setPreviewFrameListener(null)
            scope.launch {
                cameraCapture.release()
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box {
            currentFrame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Camera frame",
                    modifier = Modifier.fillMaxSize()
                )
            }

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
                        cameraCapture.selectCamera(
                            if (cameraCapture.selectedCamera.lensFacing == desidev.rtc.media.camera.CameraLensFacing.FRONT) desidev.rtc.media.camera.CameraLensFacing.BACK
                            else desidev.rtc.media.camera.CameraLensFacing.FRONT
                        )
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


