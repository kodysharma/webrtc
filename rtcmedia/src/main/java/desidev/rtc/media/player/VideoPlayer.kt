package desidev.rtc.media.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
import android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.theeasiestway.yuv.YuvUtils
import desidev.rtc.media.bitmappool.BitmapPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.Queue
import kotlin.math.max
import kotlin.math.roundToInt

class VideoPlayer(
    private val format: MediaFormat
) {
    companion object {
        val TAG = VideoPlayer::class.simpleName
    }

    private val handlerThread = HandlerThread("VideoPlayer").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val scope =
        CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { ctx, throwable ->
            Log.e(TAG, "Exception in coroutine scope", throwable)
        })

    private val imageReader = ImageReader.newInstance(
        format.getInteger(MediaFormat.KEY_WIDTH),
        format.getInteger(MediaFormat.KEY_HEIGHT),
        ImageFormat.YUV_420_888,
        2
    )
    private lateinit var videoDecoder: MediaCodec
    private val inputChannel = Channel<Pair<ByteArray, BufferInfo>>(Channel.BUFFERED)

    private val inputBuffers: Queue<Int> = LinkedList()
    private val outputBuffers: Queue<Pair<Int, BufferInfo>> = LinkedList()

    private val outputBuffersLock = Any()
    private val inputBuffersLock = Any()


    private val bitmapPool = BitmapPool(
        dimen = Size(imageReader.width, imageReader.height),
        debug = false,
        tag = "$TAG: bitmapPool"
    )

    private val currentFrame = MutableStateFlow(bitmapPool.getBitmap())
    private val currentTimestampUs = MutableStateFlow(System.nanoTime())

    init {
        imageReader.setOnImageAvailableListener({ imReader ->
            val yuvUtils = YuvUtils()
            imReader.acquireLatestImage()?.let { image ->
                image.use { img ->
                    if (currentFrame.subscriptionCount.value > 0) {
                        currentTimestampUs.value = img.timestamp / 1000

                        val frame = bitmapPool.getBitmap()
                        val rgb = yuvUtils.convertToI420(img).let { yuvUtils.yuv420ToArgb(it) }
                        frame.bitmap.apply {
                            copyPixelsFromBuffer(rgb.data)
                        }
                        currentFrame.getAndUpdate { frame }.release()
                    }
                }
            }
        }, handler)
    }

    fun play() {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        videoDecoder = MediaCodec.createDecoderByType(mime)
        videoDecoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {

//                Log.d(TAG, "onInputBufferAvailable: $index")

                synchronized(inputBuffersLock) {
                    inputBuffers.add(index)
                }
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: BufferInfo
            ) {
//                Log.d(TAG, "onOutputBufferAvailable: $index")

                synchronized(outputBuffersLock) {
                    outputBuffers.add(index to info)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "onError: $e")

                codec.reset()
                codec.setCallback(this)
                codec.configure(format, imageReader.surface, null, 0)
                codec.start()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, handler)

        videoDecoder.configure(format, imageReader.surface, null, 0)
        videoDecoder.start()

        processInputBuffer()
        processOutputBuffers()
    }

    private fun processInputBuffer() {
        try {
            scope.launch {
                while (isActive) {
                    val index = synchronized(inputBuffersLock) { inputBuffers.poll() }
                    if (index != null) {
                        try {
                            val buffer = videoDecoder.getInputBuffer(index)!!
                            val raw = inputChannel.receive()
                            val info = raw.second
                            val array = raw.first
                            buffer.put(array)


                            if (info.flags and BUFFER_FLAG_CODEC_CONFIG == BUFFER_FLAG_CODEC_CONFIG) {
                                Log.i(TAG, "codec config ignore!")
                                inputBuffers.add(index)
                                continue
                            }

                            if (info.flags and BUFFER_FLAG_PARTIAL_FRAME == BUFFER_FLAG_PARTIAL_FRAME) {
                                Log.i(TAG, "Partial Frame: ${array.size}")
                            }

                            if (info.flags and BUFFER_FLAG_KEY_FRAME == BUFFER_FLAG_KEY_FRAME) {
                                Log.i(TAG, "Key Frame: ${array.size}")
                            }

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

                    delay(10)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun processOutputBuffers() {
        scope.launch {
            while (isActive) {
                synchronized(outputBuffersLock) { outputBuffers.poll() }
                    ?.let {
                        val (index, info) = it
                        try {
                            videoDecoder.releaseOutputBuffer(index, info.presentationTimeUs)
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            ex.printStackTrace()
                        }
                    }
                delay(10)
            }
        }
    }

    suspend fun stop() {
        try {
            scope.cancel()
            try {
                videoDecoder.stop()
                videoDecoder.release()
            } catch (ex: Exception) {
                Log.e(TAG, "Could not stop video decoder")
            }

            withContext(Dispatchers.IO) {
                handlerThread.quitSafely()
                handlerThread.join()
            }
            imageReader.setOnImageAvailableListener(null, null)
            imageReader.close()

            Log.i(TAG, "Stopped")

        } catch (ex: Exception) {
            Log.e(TAG, "Exception in stop", ex)
        }
    }

    fun inputData(buffer: ByteArray, info: BufferInfo) {
        inputChannel.trySend(Pair(buffer, info))
    }

    @Composable
    fun VideoPlayerView(modifier: Modifier = Modifier) {
        val rotation = remember {
            try {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } catch (e: NullPointerException) {
                0
            }
        }

        val image = currentFrame.collectAsState().value.bitmap.asImageBitmap()
        Canvas(modifier = modifier) {
            val scale: Float = let {
                val imageDimen = if (rotation % 90 == 0) {
                    with(image) { IntSize(height, width) }
                } else {
                    with(image) { IntSize(width, height) }
                }

                val hScale = size.height / imageDimen.height
                val wScale = size.width / imageDimen.width
                max(wScale, hScale)
            }

            val dstSize =
                IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())

            val imageOffset = let {
                val x = (size.width - dstSize.width) * 0.5f
                val y = (size.height - dstSize.height) * 0.5f
                IntOffset(x.toInt(), y.toInt())
            }

            clipRect {
                rotate(rotation.toFloat(), center) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = imageOffset,
                        dstSize = dstSize
                    )
                }
            }
        }
    }
}