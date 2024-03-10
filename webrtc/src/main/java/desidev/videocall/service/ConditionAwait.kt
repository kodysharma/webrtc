package desidev.videocall.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

typealias ConditionCallback = () -> Unit

class ConditionAwait(initialValue: Boolean) {
    private var _condition: Boolean = initialValue
    private var callback = mutableListOf<ConditionCallback>()
    private val _mutex1 = Mutex()
    fun update(booleanValue: Boolean) {
        synchronized(this) {
            _condition = booleanValue
            if (_condition) {
                callback.forEach { it() }
                callback.clear()
            }
        }
    }

    suspend fun await() {
        _mutex1.withLock {
            if (!_condition) {
                coroutineScope {
                    suspendCoroutine { cont ->
                        callback.add {
                            cont.resume(Unit)
                        }
                    }
                }
            }
        }
    }
}