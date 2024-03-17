package desidev.videocall.service.rtcmsg

import android.media.MediaFormat
import desidev.turnclient.util.NumberSeqGenerator
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

val numberSeqGenerator = NumberSeqGenerator(Int.MIN_VALUE .. Int.MAX_VALUE)
@Serializable
data class RTCMessage(
    val audioSample: Sample? = null,
    val videoSample: Sample? = null,
    val control: Control? = null,
) {
    @Serializable
    data class Control(
        val txId: Int = numberSeqGenerator.next(),
        val flags: Int,
        val data: ControlData? = null,
    ) {
        companion object {
            const val STREAM_ENABLE = 1
            const val STREAM_DISABLE = 2
            const val ACKNOWLEDGE = 4
        }

        @Serializable
        data class ControlData(
            val format: Format, // data passed with STREAM_ENABLE flag
            val streamId: Int // data passed with STREAM_ENABLE/_DISABLE flag
        )
    }

    @Serializable
    data class Format(
        val map: Map<String, OneOfValue>
    )

    @Serializable
    data class OneOfValue(
        val integer: Int? = null,
        val string: String? = null,
        val byteBuffer: ByteArray? = null,
        val float: Float? = null,
        val long: Long? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as OneOfValue

            if (integer != other.integer) return false
            if (string != other.string) return false
            return byteBuffer.contentEquals(other.byteBuffer)
        }

        override fun hashCode(): Int {
            var result = integer ?: 0
            result = 31 * result + (string?.hashCode() ?: 0)
            result = 31 * result + byteBuffer.contentHashCode()
            return result
        }

        @OptIn(ExperimentalStdlibApi::class)
        override fun toString(): String {
            val oneOfValue: Any = when {
                integer!= null -> integer
                string!= null -> string
                byteBuffer!= null -> "buffer = ${byteBuffer.contentToString()}"
                float!= null -> float
                long!= null -> long
                else -> throw IllegalStateException("Unexpected value: $this")
            }
            return "OneOfValue($oneOfValue)"
        }
    }

    @Serializable
    data class Sample(
        val ptsUs: Long,
        val flags: Int,
        val buffer: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Sample

            if (ptsUs != other.ptsUs) return false
            if (flags != other.flags) return false
            return buffer.contentEquals(other.buffer)
        }

        override fun hashCode(): Int {
            var result = ptsUs.hashCode()
            result = 31 * result + flags
            result = 31 * result + buffer.contentHashCode()
            return result
        }
    }





    override fun toString(): String {
        val oneOfValue: Any = when {
            audioSample!= null -> audioSample
            videoSample!= null -> videoSample
            control!= null -> control
            else -> throw IllegalStateException("Unexpected value: $this")
        }
        return "RTCMessage($oneOfValue)"
    }
}

fun MediaFormat.toRTCFormat(): RTCMessage.Format {
    val map = mutableMapOf<String, RTCMessage.OneOfValue>()
    val format = MediaFormat::class.declaredMemberProperties
        .find { it.name == "mMap" }
        ?.let {
            it.isAccessible = true
            it.get(this) as Map<*, *>
        }
        ?.let {
            for ((key, value) in it) {
                map[key as String] = when (value) {
                    is Int -> RTCMessage.OneOfValue(integer = value)
                    is String -> RTCMessage.OneOfValue(string = value)
                    is ByteBuffer -> RTCMessage.OneOfValue(byteBuffer = value.copyArray())
                    is Float -> RTCMessage.OneOfValue(float = value)
                    is Long -> RTCMessage.OneOfValue(long = value)
                    else -> throw RuntimeException("Something wrong with MediaFormat")
                }
            }
        }

    return RTCMessage.Format(map)
}


fun RTCMessage.Format.toMediaFormat(): MediaFormat {
    val mediaFormat = MediaFormat::class.createInstance()
    for ((key, value) in this.map) {
        when {
            value.integer != null -> mediaFormat.setInteger(key, value.integer)
            value.string != null -> mediaFormat.setString(key, value.string)
            value.byteBuffer != null -> mediaFormat.setByteBuffer(
                key,
                ByteBuffer.wrap(value.byteBuffer)
            )

            value.float != null -> mediaFormat.setFloat(key, value.float)
            value.long != null -> mediaFormat.setLong(key, value.long)
        }
    }
    return mediaFormat
}


fun ByteBuffer.copyArray(): ByteArray {
    val array = ByteArray(remaining())
    get(array)
    return array
}