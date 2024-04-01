package test.videocall.signalclient

import android.util.Log
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
    companion object {
        val TAG: String? = SignalClient::class.simpleName
    }

    private val gson = GsonBuilder().create()
    private val mutableEventFlow: MutableSharedFlow<Any> = MutableSharedFlow()
    private val rpcClient = DefaultRPCClient()

    val eventFlow: SharedFlow<Any> = mutableEventFlow
    val connectionStateFlow = rpcClient.rpcConnectionEventFlow

    suspend fun connect(url: String, peerName: String) {
        rpcClient.connect("$url/$peerName")
        addSubscription()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun addSubscription() {
        rpcClient.subscribeEvent(
            EventSubscriber("offer") {
                Log.i(TAG, "Got offer event: $it")
                val offerEvent = gson.fromJson(it, OfferEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(offerEvent) }
            },
            EventSubscriber("answer") {
                Log.i(TAG, "Got answer event: $it")
                val answerEvent = gson.fromJson(it, AnswerEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(answerEvent) }
            },
            EventSubscriber("offer-cancelled") {
                Log.i(TAG, "Got offer-cancelled event: $it")
                val offerCancelledEvent = gson.fromJson(it, OfferCancelledEvent::class.java)
                GlobalScope.launch { mutableEventFlow.emit(offerCancelledEvent) }
            },
            EventSubscriber("session-closed") {
                Log.i(TAG, "Got session-closed event: $it")
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