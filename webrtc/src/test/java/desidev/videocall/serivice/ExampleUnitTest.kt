package desidev.videocall.serivice

import desidev.videocall.service.ConditionAwait
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {

    @Test
    fun conditionAwaitTest() {
        val conditionAwait = ConditionAwait(false)


        runBlocking {
            repeat(10) {
                launch {
                    conditionAwait.await()
                    println("Condition is true $it")
                }
            }

            delay(5000)
            conditionAwait.update(true)
        }
    }
}
