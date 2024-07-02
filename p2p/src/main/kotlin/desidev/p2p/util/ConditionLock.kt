package desidev.p2p.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class ConditionLock(
    state: Boolean
) {
    private val conditionState = MutableStateFlow(state)

    /**
     * Suspends the current coroutine until the condition is set to true.
     */
    suspend fun awaitTrue() {
        conditionState.asStateFlow().first { it }
    }

    suspend fun awaitFalse() {
        conditionState.asStateFlow().first { !it }
    }

    fun set(newState: Boolean) {
        conditionState.value = newState
    }

    fun get(): Boolean = conditionState.value
}