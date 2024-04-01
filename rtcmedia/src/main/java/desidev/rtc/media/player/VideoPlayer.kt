package desidev.rtc.media.player

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import desidev.rtc.media.FrameScheduler
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


sealed interface DecoderEvent {
    data class OnInputBuffAvailable(val index: Int) : DecoderEvent
    data class OnOutputBuffAvailable(val index: Int, val info: BufferInfo) : DecoderEvent
    data class OnOutputFormatChanged(val format: MediaFormat) : DecoderEvent
}

class VideoPlayer(
    private val yuvToRgbConverter: YuvToRgbConverter,
    private val format: MediaFormat
) {
    companion object {
        val TAG = VideoPlayer::class.simpleName
    }

    private val handlerThread = HandlerThread("VideoPlayer").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(Dispatchers.Default)

    private val imageReader = ImageReader.newInstance(
        format.getInteger(MediaFormat.KEY_WIDTH),
        format.getInteger(MediaFormat.KEY_HEIGHT),
        ImageFormat.YUV_420_888,
        2
    )

    private val decoderOutputSurface = imageReader.surface

    private lateinit var videoDecoder: MediaCodec

    private val inputChannel = Channel<Pair<ByteArray, BufferInfo>>(Channel.BUFFERED)

    @OptIn(ObsoleteCoroutinesApi::class)
    private val processInputActor = scope.actor {
        consumeEach { index ->
            try {
                val buffer = videoDecoder.getInputBuffer(index)!!
                val raw = inputChannel.receive()
                val info = raw.second
                val array = raw.first
                buffer.put(array)
                videoDecoder.queueInputBuffer(
                    index,
                    info.offset,
                    info.size,
                    info.presentationTimeUs,
                    info.flags
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                ex.printStackTrace()
            }
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val processOutputActor =
        scope.actor<DecoderEvent.OnOutputBuffAvailable> {
            consumeEach { event ->
                try {
                    val (index, info) = event
                    videoDecoder.releaseOutputBuffer(index, info.presentationTimeUs)
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    ex.printStackTrace()
                }
            }
        }

    private val mutFrameFlow = MutableSharedFlow<Pair<ImageBitmap, Long>>()
    val framesFlow: SharedFlow<Pair<ImageBitmap, Long>> = mutFrameFlow.asSharedFlow()

    init {
        imageReader.setOnImageAvailableListener({ imReader ->
            imReader.acquireNextImage()?.let { image ->
                if (mutFrameFlow.subscriptionCount.value > 0) {
                    val frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    yuvToRgbConverter.yuvToRgb(image, frame)
                    val pair = Pair(frame.asImageBitmap(), image.timestamp)
                    scope.launch { mutFrameFlow.emit(pair) }
                }
                image.close()
            }
        }, handler)
    }


    fun play() {
        scope.launch {
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            videoDecoder = MediaCodec.createDecoderByType(mime)

            val decoderEventFlow = callbackFlow {
                videoDecoder.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        trySendBlocking(DecoderEvent.OnInputBuffAvailable(index))
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: BufferInfo
                    ) {
                        trySendBlocking(DecoderEvent.OnOutputBuffAvailable(index, info))
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        // Todo: Handle error
                        Log.e(TAG, "onError: $e")

                        codec.reset()
                        codec.setCallback(this)
                        codec.configure(format, decoderOutputSurface, null, 0)
                        codec.start()
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        trySendBlocking(DecoderEvent.OnOutputFormatChanged(format))
                    }
                }, handler)

                videoDecoder.configure(format, decoderOutputSurface, null, 0)
                videoDecoder.start()
                awaitClose()
            }

            decoderEventFlow.collect {
                when (it) {
                    is DecoderEvent.OnInputBuffAvailable -> {
                        processInputActor.send(it.index)
                    }

                    is DecoderEvent.OnOutputBuffAvailable -> {
                        processOutputActor.send(it)
                    }

                    is DecoderEvent.OnOutputFormatChanged -> {
                    }
                }
            }
        }
    }


    suspend fun stop() {
        try {
            scope.coroutineContext.job.apply {
                cancelChildren()
                children.toList().joinAll()
            }
            scope.cancel()

            try {
                videoDecoder.stop()
                videoDecoder.release()
            } catch (ex: Exception) {
                Log.e(TAG, "Could not stop video decoder")
            }

            handlerThread.quitSafely()
            imageReader.close()

            Log.i(TAG, "Stopped")

        } catch (ex: Exception) {
            Log.e(TAG, "Exception in stop", ex)
        }
    }

    suspend fun inputData(buffer: ByteArray, info: BufferInfo) {
        try {
            inputChannel.send(Pair(buffer, info))
        } catch (ex: Exception) {
            Log.e(TAG, "Could not send buffer to input channel", ex)
        }
    }


    @Composable
    fun VideoPlayerView(modifier: Modifier = Modifier) {
        val frameScheduler = remember { FrameScheduler() }
        val currentFrame = frameScheduler.currentFrame.collectAsState(initial = null)

        LaunchedEffect(key1 = frameScheduler) {
            withContext(Dispatchers.Default) {
                framesFlow.collect {
                    val (bitmap, timestampUs) = it
                    frameScheduler.send(FrameScheduler.Action(bitmap, timestampUs))
                }
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