package desidev.videocall.service.bitmap

import android.graphics.Bitmap

interface BitmapWrapper {
    val bitmap: Bitmap
    fun release()
}