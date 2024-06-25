package test.videocall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.HelloRequest
import com.example.helloRequest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun useAppContext() {

    }

    @Test
    fun serdeTest() {
        val helloRequest = helloRequest {
            this.name = "Neeraj"
        }

        val ser = helloRequest.toByteArray()
        println("serialized size = ${ser.size}")

        println(
            HelloRequest.parseFrom(ser)
        )
    }
}