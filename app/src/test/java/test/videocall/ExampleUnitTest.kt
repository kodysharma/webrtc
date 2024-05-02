package test.videocall

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


class ExampleUnitTest {
    private val flow = callbackFlow<Int> {
        awaitClose { println("scope cancel") }
    }

    val mutex = Mutex()



    private suspend fun method1() {
        mutex.synchronized {
            println("method1")
            method2()
        }
    }

    private suspend fun method2() {
        mutex.synchronized {
            println("method2")
        }
    }

    @Test
    fun simpleTest() {
        runBlocking {
            mutex.withLock(currentCoroutineContext().job) {
                val locks = mutex.holdsLock(currentCoroutineContext().job)
                println(locks)
            }
        }
    }

}

