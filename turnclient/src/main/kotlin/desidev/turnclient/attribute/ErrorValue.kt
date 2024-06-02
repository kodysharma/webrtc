package desidev.turnclient.attribute

data class ErrorValue(
    val code: Int,
    val reason: String
) {
    companion object {
        fun from(value: ByteArray): ErrorValue {
            val reason = value.decodeToString(4, value.size - 1)
            val number = value[3].toInt()
            val classBits = value[2].toInt()
            val code = classBits * 100 + number

            return ErrorValue(code, reason)
        }
    }
}