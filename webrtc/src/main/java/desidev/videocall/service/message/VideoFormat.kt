package desidev.videocall.service.message

@SymbolId(3)
data class VideoFormat(
    @property:SymbolId(1) val mime: String,
    @property:SymbolId(2) val framerate: Int,
    @property:SymbolId(3) val rotation: Int? = null,
    @property:SymbolId(4) val width: Int,
    @property:SymbolId(5) val height: Int,
    @property:SymbolId(6) val maxWidth: Int? = null,
    @property:SymbolId(7) val maxHeight: Int? = null,
    @property:SymbolId(8) val colorFormat: Int? = null,
    @property:SymbolId(9) val bitrate: Int,
    @property:SymbolId(10) val maxBitrate: Int,
    @property:SymbolId(11) val colorRange: Int? = null,
    @property:SymbolId(12) val colorStandard: Int? = null,
    @property:SymbolId(13) val colorTransfer: Int? = null,
    @property:SymbolId(14) val csd0: ByteArray? = null,
    @property:SymbolId(15) val csd1: ByteArray? = null,
    @property:SymbolId(16) val csd2: ByteArray? = null
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoFormat

        if (mime != other.mime) return false
        if (framerate != other.framerate) return false
        if (rotation != other.rotation) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (maxWidth != other.maxWidth) return false
        if (maxHeight != other.maxHeight) return false
        if (colorFormat != other.colorFormat) return false
        if (bitrate != other.bitrate) return false
        if (maxBitrate != other.maxBitrate) return false
        if (colorRange != other.colorRange) return false
        if (colorStandard != other.colorStandard) return false
        if (colorTransfer != other.colorTransfer) return false
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
        result = 31 * result + framerate
        result = 31 * result + (rotation ?: 0)
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (maxWidth ?: 0)
        result = 31 * result + (maxHeight ?: 0)
        result = 31 * result + (colorFormat ?: 0)
        result = 31 * result + bitrate
        result = 31 * result + maxBitrate
        result = 31 * result + (colorRange ?: 0)
        result = 31 * result + (colorStandard ?: 0)
        result = 31 * result + (colorTransfer ?: 0)
        result = 31 * result + (csd0?.contentHashCode() ?: 0)
        result = 31 * result + (csd1?.contentHashCode() ?: 0)
        result = 31 * result + (csd2?.contentHashCode() ?: 0)
        return result
    }

}
