package desidev.utility

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SpeedMeter(private val label: String = "Speed") {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    companion object {
        private const val TAG = "SpeedMeter"
    }

    private var updates = AtomicInteger(0)
    private var timeInSec = AtomicReference(0.0)
    private var prev = AtomicReference(System.nanoTime())

    fun update() {
        updates.incrementAndGet()
        val now = System.nanoTime()
        val deltaTime = System.nanoTime() - prev.get()
        prev.set(now)
        timeInSec.getAndUpdate { it + deltaTime  }
        if (timeInSec.get() > 1000000000) {
            println("$TAG: $label: ${updates.get()}")
            updates.set(0)
            timeInSec.set(0.0)
        }
    }

    fun stop() {
        scope.cancel("SpeedMeter is stopped")
    }
}