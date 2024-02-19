package desidev.videocall.serivice

import desidev.videocall.service.SpeedMeter
import desidev.videocall.service.audio.CoroutineExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun coroutineTest() = runBlocking {
        val speedMeter = SpeedMeter("Test")
        val executor = CoroutineExecutor()

        repeat(1000) {

            Thread.sleep(15)

            executor.execute {
                data class Point(var x: Int, var y: Int)
                for (i in 0..1000) {
                    for (j in 0..1000) {
                        val k = i * j
                        val point = Point(i, j)
//                        println("Point: $point")
                    }
                }
            }
        }

    }
}