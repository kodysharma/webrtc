package test.videocall

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import desidev.videocall.service.Offer
import desidev.videocall.service.rtcmsg.RTCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun simpleTest() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            val job = scope.launch {
                launch {
                    while (isActive) {
                        println("C1 is active")
                    }
                    println("C1 is not active")
                }
                launch {
                    while (isActive) {
                        println("C2 is active")
                    }
                    println("C2 is not active")
                }
            }

            delay(100)
            job.cancel()
            job.join()
        }
    }
}

