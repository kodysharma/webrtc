package desidev.videocall.service.audio

import android.media.MediaFormat
import com.google.gson.GsonBuilder
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible


fun stringyFyMediaFormat(mediaFormat: MediaFormat): String {
    return try {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
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