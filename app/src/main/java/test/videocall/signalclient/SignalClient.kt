package test.videocall.signalclient

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.gson.GsonWebsocketContentConverter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class SignalClient {
    private lateinit var rpcClient: RPCClient
    private val gson = GsonBuilder().create()

    private val mutableEventFlow: MutableSharedFlow<Any> = MutableSharedFlow()
    val eventFlow: SharedFlow<Any> = mutableEventFlow

    suspend fun connect(url: String) {
        val socket = HttpClient(CIO) {
            install(WebSockets) {
                contentConverter = GsonWebsocketContentConverter()
            }
        }.webSocketSession(url)

        rpcClient = RPCClient.withSocket(socket)
        addSubscription()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun addSubscription() {
        rpcClient.subscribeEvent(
            EventSubscriber("offer") {
                val offerEvent = gson.fromJson(it, OfferEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(offerEvent) }
            },
            EventSubscriber("answer") {
                val answerEvent = gson.fromJson(it, AnswerEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(answerEvent) }
            },
            EventSubscriber("offer-cancelled") {
                val offerCancelledEvent = gson.fromJson(it, OfferCancelledEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(offerCancelledEvent) }
            },
            EventSubscriber("session-closed") {
                GlobalScope.launch {
                    mutableEventFlow.emit(SessionClosedEvent)
                }
            }
        )
    }


    suspend fun postOffer(params: PostOfferParams) {
        val methodReturn = rpcClient.methodCall("post-offer", gson.toJsonTree(params).asJsonObject)
        val error = methodReturn.get("error")
        if (error != null) {
            throw RuntimeException("Method returned an error: $error")
        }
    }

    suspend fun postAnswer(answer: PostAnswerParams) {
        val methodReturn = rpcClient.methodCall("post-answer", gson.toJsonTree(answer).asJsonObject)
        if (methodReturn.has("error")) {
            val error = methodReturn.get("error")
            throw RuntimeException("Method returned an error: $error")
        }
    }

    suspend fun cancelRecentOffer() {
        rpcClient.methodCall("cancel-recent-offer", JsonObject())
    }

    suspend fun postCloseSession() {
        rpcClient.methodCall("post-close-session", JsonObject())
    }

    suspend fun getPeers(): List<Peer> {
        val result = rpcClient.methodCall("get-peers", JsonObject())
        return if (result.has("result")) {
            val type = object : TypeToken<List<Peer>>() {}.type
            gson.fromJson(result.get("result"), type)
        } else {
            emptyList()
        }
    }
}