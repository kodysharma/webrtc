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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.roundToLong


sealed interface DecoderEvent {
    data class OnInputBuffAvailable(val index: Int) : DecoderEvent
    data class OnOutputBuffAvailable(val index: Int, val info: BufferInfo) : DecoderEvent
    data class OnOutputFormatChanged(val format: MediaFormat) : DecoderEvent
}

class VideoPlayer(private val format: MediaFormat) {
    companion object {
        val TAG = VideoPlayer::class.simpleName
    }

    private val handlerThread = HandlerThread("VideoPlayer").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val handlerDispatcher = handler.asCoroutineDispatcher()
    private val scope = CoroutineScope(handlerDispatcher)

    private var frameListener: ((Image) -> Unit)? = null

    private val imageReader = ImageReader.newInstance(
        format.getInteger(MediaFormat.KEY_WIDTH),
        format.getInteger(MediaFormat.KEY_HEIGHT),
        ImageFormat.YUV_420_888,
        2
    ).apply {
        setOnImageAvailableListener({ imageReader ->
            imageReader.acquireNextImage()?.let { image ->
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
            Log.e(TAG, "Exception in coroutine", throwable)
        }

    private val inputChannel = Channel<Pair<ByteArray, BufferInfo>>(Channel.BUFFERED)

    @OptIn(ObsoleteCoroutinesApi::class)
    private val processInputActor = scope.actor(handlerDispatcher) {
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
        scope.actor<DecoderEvent.OnOutputBuffAvailable>(handlerDispatcher) {
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


    fun play() {
        scope.launch(coroutineExceptionHandler) {
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
                })

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

    fun inputData(buffer: ByteArray, info: BufferInfo) {
        try {
            inputChannel.trySendBlocking(buffer to info)
        } catch (ex: Exception) {
            Log.e(TAG, "Could not send buffer to input channel", ex)
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    fun frameScheduler(onNextFrame: (ImageBitmap) -> Unit) = scope.actor<Pair<ImageBitmap, Long>> {
        var previousTimestamp = -1L
        consumeEach { (bitmap, timeStampUs) ->
            if (previousTimestamp < 0) {
                onNextFrame(bitmap)
                previousTimestamp = timeStampUs
            } else {
                val delay = timeStampUs - previousTimestamp
                if (delay > 0) {
                    delay(delay)
                }
            }
        }
    }

    @Composable
    fun VideoPlayerView(modifier: Modifier = Modifier) {
        val localContext = LocalContext.current
        val yuvToRgbConverter = remember { desidev.utility.yuv.YuvToRgbConverter(localContext) }
        val currentFrame = remember {
            mutableStateOf<ImageBitmap?>(null)
        }

        val frameScheduler = remember {
            frameScheduler { nextFrame ->
                currentFrame.value = nextFrame
            }
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
                try {
                    image.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not close image", e)
                }
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