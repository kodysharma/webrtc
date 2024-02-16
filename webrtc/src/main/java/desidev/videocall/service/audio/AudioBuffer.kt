package desidev.videocall.service.audio

data class AudioBuffer(val buffer: ByteArray, val ptsUs: Long, val flags: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioBuffer

        if (!buffer.contentEquals(other.buffer)) return false
        if (ptsUs != other.ptsUs) return false
        if (flags != other.flags) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + flags
        return result
    }
}