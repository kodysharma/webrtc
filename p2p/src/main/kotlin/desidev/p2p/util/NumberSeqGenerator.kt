package desidev.p2p.util

class NumberSeqGenerator(
    private val range: IntRange
) {
    private var seq = range.first
    fun next(): Int {
        seq += range.step
        if (seq > range.last) seq = range.first
        return seq
    }
}