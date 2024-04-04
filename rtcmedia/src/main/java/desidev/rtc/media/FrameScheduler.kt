package desidev.rtc.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.microseconds

class FrameScheduler(coroutineContext: CoroutineContext = Dispatchers.Default) :
    Actor<FrameScheduler.Action>(coroutineContext) {
    companion object {
        val TAG = FrameScheduler::class.simpleName
    }

    data class Action(val bitmap: ImageBitmap, val timeStampUs: Long)

    private val frameChannel = Channel<ImageBitmap>()
    val currentFrame = frameChannel.receiveAsFlow()

    private var timeOffsetUs: Long = 0

    override suspend fun onNextAction(action: Action) {
        val (imageBitmap, timestampUs) = action
        val currentTimeUs = System.nanoTime() / 1000
        if (timeOffsetUs == 0L) {
            timeOffsetUs = currentTimeUs - timestampUs
        }

        val delayTimeUs = (timeOffsetUs + timestampUs) - currentTimeUs
        if (delayTimeUs > 0) {
//            delay(delayTimeUs.microseconds)
        }

        frameChannel.send(imageBitmap)
//        Log.d(TAG, "delayTimeUs: $delayTimeUs")
    }
}