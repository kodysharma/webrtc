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
import desidev.videocall.service.codec.Codec
import desidev.videocall.service.codec.configure
import desidev.videocall.service.codec.createAudioEncoder
import desidev.videocall.service.ext.asMilliSec
import desidev.videocall.service.ext.toLong
import desidev.videocall.service.mediasrc.AudioBuffer
import desidev.videocall.service.mediasrc.SendingPort
import desidev.videocall.service.mediasrc.VoiceRecorder
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
    val voiceRecorderOutput = remember { SendingPort<AudioBuffer>() }
    val voiceRecorder = remember {
        VoiceRecorder.Builder().setChunkLenInMs(20.asMilliSec.toLong()).build()
    }
    val encoder = remember {
        Codec.createAudioEncoder().apply {
            setInPort(voiceRecorderOutput)
            configure(voiceRecorder.format)
        }
    }

    var muxer by remember {
        mutableStateOf(MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/audio.mp4"))
    }

    var isRecording by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(Timer()) }
    var time by remember { mutableLongStateOf(0L) }


    suspend fun flowFromEncoderToMuxer() {
        withContext(Dispatchers.IO) {
            val audioTrack = muxer.addTrack(encoder.mediaFormat().get())
            muxer.start()

            Log.d(TAG, "flowFromEncoderToMuxer: Muxer started")

            val speedMeter = SpeedMeter("flowFromEncoderToMuxer")

            encoder.run {
                while (outPort.isOpenForReceive) {
                    try {
                        val chunk = outPort.receive()
                        muxer.writeSampleData(
                            audioTrack,
                            ByteBuffer.wrap(chunk.buffer),
                            MediaCodec.BufferInfo().apply {
                                size = chunk.buffer.size
                                presentationTimeUs = chunk.ptsUs
                                flags = chunk.flags
                            }
                        )
                    } catch (ex: Exception) {
                        Log.d(TAG, "flowFromEncoderToMuxer: exception: $ex")
                    }

                    speedMeter.update()
                }
            }

            muxer.stop()
            muxer = MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/audio.mp4")
            Log.d(TAG, "flowFromEncoderToMuxer: Muxer stopped and reset")
        }
    }


    fun startRecording() {
        isRecording = true
        voiceRecorder.start()
        encoder.setInPort(voiceRecorder.outPort)
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

        with(scope) {
            launch { flowFromEncoderToMuxer() }
        }
    }

    fun stopRecording() {
        isRecording = false
        voiceRecorder.stop()
        voiceRecorderOutput.close()
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