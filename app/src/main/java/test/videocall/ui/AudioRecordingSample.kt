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
import desidev.videocall.service.SpeedMeter
import desidev.videocall.service.audio.AudioEncoder
import desidev.videocall.service.audio.VoiceRecorder
import desidev.videocall.service.audio.defaultAudioEncoder
import desidev.videocall.service.ext.asMilliSec
import desidev.videocall.service.ext.toLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.MediaMuxerWrapper
import test.videocall.ui.theme.AppTheme
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask

private const val TAG = "AudioRecordingSample"

@Composable
fun AudioRecordingSample() {
    val scope = rememberCoroutineScope()
    val voiceRecorder = remember {
        VoiceRecorder.Builder().setChunkLenInMs(20.asMilliSec.toLong()).build()
    }
    val encoder = remember { AudioEncoder.defaultAudioEncoder() }
    var muxer by remember {
        mutableStateOf(MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/audio.mp4"))
    }

    var isRecording by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(Timer()) }
    var time by remember { mutableLongStateOf(0L) }


    suspend fun flowFromRecorderToEncoder() {
        val speedMeter = SpeedMeter("flowFromRecorderToEncoder")

        withContext(Dispatchers.Default) {
            for (chunk in voiceRecorder.chunkFlow) {
                encoder.enqueRawBuffer(chunk)
                speedMeter.update()
            }
        }
    }


    suspend fun flowFromEncoderToMuxer() {
        withContext(Dispatchers.Default) {
            val audioTrack = muxer.addTrack(encoder.mediaFormat())
            muxer.start()

            val speedMeter = SpeedMeter("flowFromEncoderToMuxer")

            for (encodedChunk in encoder.encodedData) {
                muxer.writeSampleData(
                    audioTrack,
                    ByteBuffer.wrap(encodedChunk.buffer),
                    MediaCodec.BufferInfo().apply {
                        size = encodedChunk.buffer.size
                        presentationTimeUs = encodedChunk.ptsUs
                        flags = encodedChunk.flags
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
        scope.launch {
            isRecording = true
            voiceRecorder.start()
            encoder.configure(voiceRecorder.format)
            encoder.startEncoder()

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

            launch { flowFromRecorderToEncoder() }
            flowFromEncoderToMuxer()
        }
    }

    fun stopRecording() {
        isRecording = false
        voiceRecorder.stop()
        encoder.stopEncoder()
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