package test.videocall

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import desidev.videocall.service.Offer
import org.junit.Assert.assertEquals
import org.junit.Test
import test.videocall.ui.JsonWrapper
import test.videocall.ui.Peer

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
    fun jsonSerdeTest() {
        val gson = Gson()
        val peer = Peer("123", "456")
        val wrapper = JsonWrapper(peer)

        val jsonString = gson.toJson(wrapper)
        println(jsonString)

        val deWrapper = gson.fromJson(jsonString, JsonWrapper::class.java)
        println(deWrapper)

        when (deWrapper.type) {
            Peer::class.simpleName!! -> {
                val peer = deWrapper.data as Peer
                assertEquals(peer.id, "123")
                assertEquals(peer.name, "456")
            }
        }
    }

    @Test
    fun jsonSerdeTest2() {
        val gson = Gson()
        val offer = Offer("123", emptyList(), 323, 32, Peer("123", "456"))
        val typeToken = object : TypeToken<Offer<Peer>>() {}.type

        val ser = gson.toJson(mapOf(
            "type" to "offer",
            "data" to offer
        ))

        val der = gson.fromJson(ser, JsonObject::class.java)
        when(val type = der.get("type").asString) {
            "offer" -> {
                val offer: Offer<Peer> = gson.fromJson(der, getJavaType())
                println(offer)
            }
        }

        println(typeToken)
    }
}

inline fun <reified T>getJavaType() = T::class.java

