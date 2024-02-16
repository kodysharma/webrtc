package desidev.videocall.service.message

@SymbolId(1)
data class AudioFormat(
    @property:SymbolId(1) val mime: String,
    @property:SymbolId(2) val bitrate: Int,
    @property:SymbolId(3) val channelCount: Int,
    @property:SymbolId(4) val sampleRate: Int,
    @property:SymbolId(5) val csd0: ByteArray? = null,
    @property:SymbolId(6) val csd1: ByteArray? = null,
    @property:SymbolId(7) val csd2: ByteArray? = null
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioFormat

        if (mime != other.mime) return false
        if (bitrate != other.bitrate) return false
        if (channelCount != other.channelCount) return false
        if (sampleRate != other.sampleRate) return false
        if (csd0 != null) {
            if (other.csd0 == null) return false
            if (!csd0.contentEquals(other.csd0)) return false
        } else if (other.csd0 != null) return false
        if (csd1 != null) {
            if (other.csd1 == null) return false
            if (!csd1.contentEquals(other.csd1)) return false
        } else if (other.csd1 != null) return false
        if (csd2 != null) {
            if (other.csd2 == null) return false
            if (!csd2.contentEquals(other.csd2)) return false
        } else if (other.csd2 != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + bitrate
        result = 31 * result + channelCount
        result = 31 * result + sampleRate
        result = 31 * result + (csd0?.contentHashCode() ?: 0)
        result = 31 * result + (csd1?.contentHashCode() ?: 0)
        result = 31 * result + (csd2?.contentHashCode() ?: 0)
        return result
    }
}