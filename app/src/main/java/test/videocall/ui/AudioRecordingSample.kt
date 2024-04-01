package test.videocall.ui

import android.media.MediaCodec
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import desidev.rtc.media.AudioEncoderActor
import desidev.rtc.media.AudioEncoderActor.EncoderAction
import desidev.rtc.media.VoiceRecorder
import desidev.rtc.media.codec.createAudioMediaFormat
import desidev.utility.asMilliSec
import desidev.utility.toLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.MediaMuxerWrapper
import test.videocall.ui.theme.AppTheme
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask

private const val TAG = "AudioRecordingSample"

data class App(
    val voiceRecorder: VoiceRecorder,
    var encoderActor: AudioEncoderActor? = null,
)

@Composable
fun AudioRecordingSample() {
    val scope = rememberCoroutineScope()
    val app = remember {
        val voiceRecorder = VoiceRecorder.Builder().setChunkLenInMs(20.asMilliSec.toLong()).build()
        App(voiceRecorder)
    }

    var muxer by remember {
        mutableStateOf(MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/audio.mp4"))
    }

    var isRecording by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(Timer()) }
    var time by remember { mutableLongStateOf(0L) }


    suspend fun flowFromEncoderToMuxer() {
        withContext(Dispatchers.IO) {
            val encoder = app.encoderActor!!
            val format = encoder.outputFormat.await()
            val audioTrack = muxer.addTrack(format)
            muxer.start()

            Log.d(TAG, "flowFromEncoderToMuxer: Muxer started")

            val speedMeter = desidev.utility.SpeedMeter("flowFromEncoderToMuxer")

            encoder.outputChannel.consumeEach { chunk ->
                muxer.writeSampleData(
                    audioTrack,
                    ByteBuffer.wrap(chunk.buffer),
                    MediaCodec.BufferInfo().apply {
                        size = chunk.buffer.size
                        presentationTimeUs = chunk.ptsUs
                        flags = chunk.flags
                    }
                )

                speedMeter.update()
            }

            muxer.stop()
            muxer = MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/audio.mp4")
            Log.d(TAG, "flowFromEncoderToMuxer: Muxer stopped and reset")
        }
    }


    fun startRecording() {
        isRecording = true

        app.encoderActor = AudioEncoderActor(app.voiceRecorder.outChannel)

        scope.launch {
            app.encoderActor!!.send(
                EncoderAction.Configure(
                    createAudioMediaFormat(
                        app.voiceRecorder.format
                    )
                )
            )

            app.encoderActor!!.send(EncoderAction.Start)

            flowFromEncoderToMuxer()
        }

        app.voiceRecorder.start()

        time = 0
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    time += 1
                }
            },
            0,
            1000
        )
    }

    fun stopRecording() {
        isRecording = false
        app.apply {
            voiceRecorder.stop()
            encoderActor!!.trySendBlocking(EncoderAction.Stop)
            encoderActor = null

            Log.d(TAG, "Recording stopped")
        }
        timer.cancel()
        timer = Timer()
    }

    AppTheme {
        Surface {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column {
                    if (isRecording) {
                        Text(text = "Recording.. : $time seconds")
                    }

                    Button(onClick = {
                        if (isRecording) {
                            stopRecording()
                        } else {
                            startRecording()
                        }
                    }) {
                        Text(text = "Start/Stop Recording")
                    }

                }

            }
        }
    }
}