package desidev.videocall.service.message

import desidev.videocall.service.message.Message
import desidev.videocall.service.message.SymbolId

@SymbolId(4)
data class VideoSample(
    @property:SymbolId(1) val timeStamp: Long,
    @property:SymbolId(2) val flag: Int,
    @property:SymbolId(3) val sample: ByteArray
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoSample

        if (timeStamp != other.timeStamp) return false
        if (flag != other.flag) return false
        if (!sample.contentEquals(other.sample)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timeStamp.hashCode()
        result = 31 * result + flag
        result = 31 * result + sample.contentHashCode()
        return result
    }
}