package test.videocall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
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
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun channelTest() {
        val channel = Channel<Int>()
        val scope = CoroutineScope(Dispatchers.Default)

        channel.close()

        runBlocking {
            try {
                channel.receive()
            } catch (e: ClosedReceiveChannelException) {
                println("Channel is closed")
            }
        }
    }
}