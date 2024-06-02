package test.videocall

import desidev.utility.SpeedMeter
import kotlinx.coroutines.runBlocking
import org.junit.Test


class ExampleUnitTest {
    private val speedMeter = SpeedMeter()


    @Test
    fun simpleTest() {
        runBlocking {
            while (true) {
                speedMeter.update()
                Thread.sleep(16)
            }
        }
    }

}

