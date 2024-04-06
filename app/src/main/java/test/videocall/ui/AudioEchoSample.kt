package test.videocall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import desidev.rtc.media.VoiceRecorder
import desidev.rtc.media.player.AudioPlayer
import desidev.utility.asMilliSec
import desidev.utility.toLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.ui.theme.AppTheme

private const val TAG = "AudioRecordingSample"

data class App(
    val voiceRecorder: VoiceRecorder,
    var audioPlayer: AudioPlayer? = null
)

@Composable
fun AudioEchoSample() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember {
        val voiceRecorder = VoiceRecorder.Builder().build()
        App(voiceRecorder)
    }

    var isRecording by remember { mutableStateOf(false) }

    fun startRecording() {
        isRecording = true
        scope.launch {
            app.voiceRecorder.start()
            app.audioPlayer = AudioPlayer(app.voiceRecorder.getCompressFormat().await())
            app.audioPlayer?.startPlayback()

            withContext(Dispatchers.Default) {
                app.voiceRecorder.getCompressChannel().consumeEach {
                    app.audioPlayer?.queueAudioBuffer(it)
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        scope.launch {
            app.voiceRecorder.stop()
            app.audioPlayer?.stop()
            app.audioPlayer = null
        }
    }


    val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
    if (!permissions.all {
            ActivityCompat.checkSelfPermission(
                context,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }) {
        val permissionLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) {
                Log.d(TAG, "onRequestPermissionsResult: $it")
            }

        LaunchedEffect(Unit) {
            permissionLauncher.launch(permissions)
        }
    }

    AppTheme {
        Surface {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column {
                    val btnText = if (isRecording) "Stop" else "Start"

                    Button(onClick = {
                        if (isRecording) {
                            stopRecording()
                        } else {
                            startRecording()
                        }
                    }) {
                        Text(text = btnText)
                    }
                }
            }
        }
    }
}