package desidev.utility


class SpeedMeter(private val label: String = "Speed") {
    companion object {
        private const val TAG = "SpeedMeter"
        private const val ONE_SECOND_NANOS = 1_000_000_000L
    }

    private var updates = 0L
    private var timePassed = 0L
    private var prev = System.nanoTime()

    fun update() {
        synchronized(this) {
            updates++
            val now = System.nanoTime()
            val deltaTime = now - prev
            prev = now
            timePassed += deltaTime

            if (timePassed >= ONE_SECOND_NANOS) {
                println("$TAG: $label: $updates")
                updates = 0L
                timePassed = 0L
            }
        }
    }
}