package desidev.rtc.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlin.coroutines.CoroutineContext

abstract class Actor<A>(coroutineContext: CoroutineContext){

    protected val actorScope = CoroutineScope(coroutineContext)

    @OptIn(ObsoleteCoroutinesApi::class)
    private val actor = actorScope.actor(capacity = Channel.BUFFERED) {
        consumeEach { action ->
            onNextAction(action)
        }
    }


    abstract suspend fun onNextAction(action: A)
    suspend fun send(a: A) {
        actor.send(a)
    }
    fun trySendBlocking(a: A) = actor.trySendBlocking(a)
    fun trySend(a: A) = actor.trySend(a)


    fun close() {
        actor.close()
        actorScope.cancel()
    }
}

open class Action<R> {
    val deferred: CompletableDeferred<R> = CompletableDeferred()
    suspend fun await(): R {
        return deferred.await()
    }

    suspend fun complete(result: R) {
        deferred.complete(result)
    }

    suspend fun completeExceptionally(ex: Throwable) {
        deferred.completeExceptionally(ex)
    }
}


