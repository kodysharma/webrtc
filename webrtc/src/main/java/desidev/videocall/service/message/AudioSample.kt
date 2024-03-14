package desidev.videocall.service.message

@SymbolId(21)
data class AudioSample(
    @property:SymbolId(1) val timeStampUs: Long,
    @property:SymbolId(2) val flag: Int,
    @property:SymbolId(3) val sample: ByteArray
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioSample

        if (timeStampUs != other.timeStampUs) return false
        if (flag != other.flag) return false
        if (!sample.contentEquals(other.sample)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timeStampUs.hashCode()
        result = 31 * result + flag
        result = 31 * result + sample.contentHashCode()
        return result
    }
}