package desidev.rtc.media.bitmappool

import android.graphics.Bitmap

interface BitmapWrapper {
    val bitmap: Bitmap
    fun release()
}