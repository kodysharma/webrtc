package desidev.rtc.media.player

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong


sealed interface CodecEvent {
    data class OnInputBuffAvailable(val index: Int): CodecEvent
    data class OnOutputBuffAvailable(val index: Int, val info: BufferInfo): CodecEvent
    data class OnOutputFormatChanged(val format: MediaFormat): CodecEvent
}

class VideoPlayer(private val inputFormat: MediaFormat) {
    companion object {
        val TAG = VideoPlayer::class.simpleName
    }

    private val handlerThread = HandlerThread("VideoPlayer").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val scope = CoroutineScope(handler.asCoroutineDispatcher())

    private var frameListener: ((Image) -> Unit)? = null

    private val imageReader = ImageReader.newInstance(
        inputFormat.getInteger(MediaFormat.KEY_WIDTH),
        inputFormat.getInteger(MediaFormat.KEY_HEIGHT),
        ImageFormat.YUV_420_888,
        2
    ).apply {
        setOnImageAvailableListener({ imageReader ->
            imageReader.acquireLatestImage()?.let { image ->
                if (frameListener != null) {
                    frameListener!!.invoke(image)
                } else {
                    image.close()
                }
            }
        }, handler)
    }

    private val decoderOutputSurface = imageReader.surface

    private lateinit var videoDecoder: MediaCodec
    private val coroutineExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            inputChannel.close()
        }

    private val inputChannel = Channel<Pair<ByteArray, BufferInfo>>(Channel.CONFLATED)
    private lateinit var inputBufferAvailableFlow: Flow<Int>
    private lateinit var decoderOutputBufferAvailableFlow: Flow<Int>

    fun play() {
        Log.d(TAG, "play: video decoder input format: $inputFormat")
        scope.launch(coroutineExceptionHandler) {
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            videoDecoder = MediaCodec.createDecoderByType(mime)
            inputFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )


            videoDecoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {

                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: BufferInfo
                ) {
                    TODO("Not yet implemented")
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    TODO("Not yet implemented")
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    TODO("Not yet implemented")
                }

            })

            videoDecoder.configure(inputFormat, decoderOutputSurface, null, 0)
            videoDecoder.start()

            val timeout = 1_000_000L
            var timeOffset = -1L
            for (input in inputChannel) {
                val status = videoDecoder.dequeueInputBuffer(timeout)
                if (status >= 0) {
                    val info = input.second
                    val array = input.first
                    val buffer = videoDecoder.getInputBuffer(status)!!
                    buffer.clear()
                    buffer.put(array)
                    buffer.limit(info.size)
                    buffer.position(info.offset)
                    videoDecoder.queueInputBuffer(
                        status, info.offset, info.size, info.presentationTimeUs, info.flags
                    )
                } else {
                    Log.d(TAG, "play: no input buffers!")
                }


                val info = BufferInfo()
                val output_buffer = videoDecoder.dequeueOutputBuffer(info, timeout)
                if (output_buffer == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "play: decoder output format changed: ${videoDecoder.outputFormat}")
                } else if (output_buffer >= 0) {
                    if (timeOffset < 0) {
                        timeOffset =
                            (System.nanoTime() * 0.001 - info.presentationTimeUs).roundToLong()
                    }
                    val frameTimeStamp = info.presentationTimeUs + timeOffset
                    val delayTime = (frameTimeStamp - System.nanoTime() * 0.001) // microsecond
//                    Log.d(TAG, "frame timpestamp = $frameTimeStamp, delayTime: $delayTime, timeOffset: $timeOffset" )
                    delay((delayTime * 0.001).roundToLong())
                    videoDecoder.releaseOutputBuffer(output_buffer, frameTimeStamp)
                } else {
                    Log.d(TAG, "play: no output buffers = $output_buffer")
                }
            }
            videoDecoder.stop()
            videoDecoder.release()
        }
    }

    fun stop() {
        inputChannel.close()
        scope.cancel()
        imageReader.close()
        handlerThread.quitSafely()
    }

    fun inputData(buffer: ByteArray, info: BufferInfo) {
        try {
            inputChannel.trySendBlocking(buffer to info)
            Log.d(TAG, "inputData: ${info.size}")
        } catch (ex: Exception) {
            Log.i(TAG, "Error: ${ex.message}")
        }
    }

    @Composable
    fun VideoPlayerView(modifier: Modifier = Modifier) {
        val localContext = LocalContext.current
        val yuvToRgbConverter = remember { desidev.utility.yuv.YuvToRgbConverter(localContext) }
        val currentFrame = remember {
            mutableStateOf<ImageBitmap?>(null)
        }

        fun Image.toBitmap(): Bitmap {
            val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            yuvToRgbConverter.yuvToRgb(this, outputBitmap)
            return outputBitmap
        }

        DisposableEffect(Unit) {
            Log.d(TAG, "VideoPlayerView added")

            frameListener = { image ->
                currentFrame.value = image.toBitmap().asImageBitmap()
                image.close()
            }
            onDispose {
                frameListener = null
                Log.d(TAG, "VideoPlayerView removed")
            }
        }

        currentFrame.value?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }
    }
}