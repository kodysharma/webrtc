package desidev.rtc.media

import android.media.MediaFormat
import com.google.gson.GsonBuilder
import java.nio.ByteBuffer
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible


fun stringyFyMediaFormat(mediaFormat: MediaFormat): String {
    return try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        MediaFormat::class.declaredFunctions.find {
            it.name == "getMap"
        }?.apply { isAccessible = true }?.call(mediaFormat)?.let { mediaData ->
            gson.toJson(mediaData)
        }
            ?: "MediaFormat: $mediaFormat"
    } catch (ex: Exception) {
        ex.printStackTrace()
        "Error: ${ex.message}"
    }
}


fun ByteBuffer.copy() : ByteBuffer {
    val newBuffer = if (isDirect) ByteBuffer.allocateDirect(capacity()) else ByteBuffer.allocate(capacity())

    val position = position()
    val limit = limit()
    rewind()
    newBuffer.put(this)

    position(position)
    limit(limit)

    newBuffer.position(0)
    newBuffer.limit(limit)
    return newBuffer
}