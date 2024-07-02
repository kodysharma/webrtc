package desidev.p2p.util

class Timer {
    @Volatile
    private var lastTick: Long = 0

    @Volatile
    private var isTicked: Boolean = false

    @Synchronized
    fun tick() {
        check(!isTicked) { "Timer has already been ticked and not lapsed yet." }
        lastTick = System.nanoTime()
        isTicked = true
    }

    @Synchronized
    fun lapse(): Long {
        check(isTicked) { "Timer has not been ticked yet." }
        val now = System.nanoTime()
        val duration = (now - lastTick)
        isTicked = false
        return duration
    }

    @Synchronized
    fun runIfLapse(nano: Long, block: () -> Unit) {
        val now = System.nanoTime()
        val duration = (now - lastTick)
        if (duration > nano) {
            block()
            isTicked = false
        }
    }
}