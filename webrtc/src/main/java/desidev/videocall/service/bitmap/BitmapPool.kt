package desidev.videocall.service.bitmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Size
import java.util.concurrent.ConcurrentLinkedQueue

class BitmapPool(
    val dimen: Size,                                                    // Dimension of the bitmaps inside the pool
    val config: Bitmap.Config = Bitmap.Config.ARGB_8888,                // Bitmap configuration
    private val capacity: Int = 10,                                             // Maximum number of bitmaps in the pool
    initialSize: Int = 4
) {
    private val pool = ConcurrentLinkedQueue<Bitmap>()

    init {
        for (i in 0 until initialSize) {
            pool.add(Bitmap.createBitmap(dimen.width, dimen.height, config))
        }
    }

    /**
     * Get a bitmap from the pool, or create a new one if the pool is empty
     */
    fun getBitmap(): BitmapWrapper {
        return (pool.poll() ?: Bitmap.createBitmap(
            dimen.width,
            dimen.height,
            config
        )).let {
            newBitmap()
        }
    }


    fun makeCopyOf(bitmapWrapper: BitmapWrapper): BitmapWrapper {
        return bitmapWrapper.bitmap.let { src ->
            getBitmap().apply {
                Canvas(bitmap).drawBitmap(src, 0f, 0f, null)
            }
        }
    }


    private fun newBitmap(): BitmapWrapper = object : BitmapWrapper {
        override val bitmap: Bitmap =
            Bitmap.createBitmap(dimen.width, dimen.height, config)

        override fun release() {
            returnToPool(bitmap)
        }
    }

    /**
     * Return a bitmap to the pool
     */
    private fun returnToPool(bitmap: Bitmap) {
        if (bitmap.width != dimen.width || bitmap.height != dimen.height) {
            return
        }

        if (pool.size >= capacity) {
            println("bitmap dropped from pool")
            bitmap.recycle()
            return
        }
        pool.add(bitmap)
    }
}