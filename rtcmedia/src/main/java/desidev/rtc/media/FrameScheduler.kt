package desidev.rtc.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

class FrameUpdater(scope: CoroutineScope): Actor<FrameUpdater.Action>(scope) {

    data class Action(val bitmap: ImageBitmap, val timeStampUs: Long)

    private var previousTimestamp = -1L
    override suspend fun onNextAction(action: Action) {
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