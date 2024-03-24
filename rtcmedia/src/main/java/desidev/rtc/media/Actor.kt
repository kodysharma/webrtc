package desidev.rtc.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking

abstract class Actor<A>(scope: CoroutineScope) {

    @OptIn(ObsoleteCoroutinesApi::class)
    private val actor = scope.actor {
        // process event
        consumeEach { ev ->
            onNextAction(ev)
        }
    }

    abstract suspend fun onNextAction(action: A)

    fun send(e: A) {
        actor.trySendBlocking(e)
    }

    fun close() {
        actor.close()
    }
}


