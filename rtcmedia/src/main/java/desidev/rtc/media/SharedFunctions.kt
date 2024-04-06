package desidev.rtc.media

import android.media.MediaFormat
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
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