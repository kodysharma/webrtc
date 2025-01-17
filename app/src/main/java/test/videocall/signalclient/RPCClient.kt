package test.videocall.signalclient

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.gson.GsonWebsocketContentConverter
import io.ktor.util.collections.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed class RPCConnectionEvent {
    data object OnConnect: RPCConnectionEvent()
    data object OnDisconnect: RPCConnectionEvent()
}

interface RPCClient {
    val rpcConnectionEventFlow: SharedFlow<RPCConnectionEvent>
    suspend fun methodCall(name: String, params: JsonObject): JsonObject
    suspend fun subscribeEvent(vararg subscriber: EventSubscriber): JsonObject
    suspend fun dispose()
}


class DefaultRPCClient : RPCClient {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val methodResultDispatch: MutableMap<Any, (JsonObject) -> Unit> = ConcurrentMap()
    private val eventDispatch: MutableMap<Any, (JsonElement) -> Unit> = ConcurrentMap()
    private val mutRPCConnectionEventFlow: MutableSharedFlow<RPCConnectionEvent> = MutableSharedFlow()

    override val rpcConnectionEventFlow: SharedFlow<RPCConnectionEvent> = mutRPCConnectionEventFlow.asSharedFlow()
    private var socketSession: DefaultClientWebSocketSession? = null

    suspend fun connect(url: String) {
        socketSession = HttpClient(CIO) {
            install(WebSockets) {
                contentConverter = GsonWebsocketContentConverter()
            }
        }.webSocketSession(url)
        mutRPCConnectionEventFlow.emit(RPCConnectionEvent.OnConnect)
        dispatchResult()
    }

    override suspend fun methodCall(name: String, params: JsonObject): JsonObject {
        val id = UUID.randomUUID().toString()
        val methodCall = JsonObject().apply {
            addProperty("method", name)
            addProperty("id", id)
            add("params", params)
        }
        this.socketSession?.sendSerialized(methodCall)
        return suspendCoroutine { cont ->
            methodResultDispatch[id] = { json: JsonObject ->
                cont.resume(json)
            }
        }
    }

    override suspend fun subscribeEvent(vararg subscriber: EventSubscriber): JsonObject {
        val session = socketSession ?: throw IllegalStateException("Not connected")

        val subscriptions = JsonArray()
        for (sub in subscriber) {
            val subscriptionId = UUID.randomUUID().toString()

            subscriptions.add(JsonObject().apply {
                addProperty("type", sub.type)
                addProperty("id", subscriptionId)
            })

            eventDispatch[subscriptionId] = { json ->
                sub.next(json)
            }
        }

        val callId = UUID.randomUUID().toString()
        val methodCall = JsonObject().apply {
            addProperty("method", "subscribe")
            addProperty("id", callId)
            add("params", JsonObject().apply {
                add("subscriptions", subscriptions)
            })
        }

        session.sendSerialized(methodCall)
        return suspendCoroutine { cont ->
            methodResultDispatch[callId] = { json: JsonObject ->
                cont.resume(json)
            }
        }
    }

    override suspend fun dispose() {
        scope.cancel()
    }


    private fun dispatchResult() {
        scope.launch {
            while (true) {
                try {
                    val jsonObject = socketSession?.receiveDeserialized<JsonObject>() ?: break
                    val id = jsonObject.get("id").asString
//                    Log.d(TAG, "received object: $jsonObject")

                    val objectType = jsonObject.get("type").asString
                    if (objectType == "method-return") {
                        val callback = methodResultDispatch.remove(id)
                        if (callback != null) {
                            val resultObject = JsonObject()
                            if (jsonObject.has("result")) {
                                jsonObject.get("result")?.let {
                                    resultObject.add("result", it)
                                }
                            }

                            if (jsonObject.has("error")) {
                                jsonObject.get("error").asJsonObject?.let {
                                    resultObject.add("error", it)
                                }
                            }
                            callback(resultObject)
                        }
                    } else if (objectType == "event") {
                        val callback = eventDispatch[id]
                        if (callback != null) {
                            callback(jsonObject.get("next") ?: JsonObject())
                        }
                    }

                } catch (ex: Exception) {
                    ex.printStackTrace()
                    break
                }
            }
            mutRPCConnectionEventFlow.emit(RPCConnectionEvent.OnDisconnect)
            methodResultDispatch.clear()
        }
    }


    companion object {
        val TAG: String = DefaultRPCClient::class.java.simpleName
    }
}

class EventSubscriber(val type: String, val next: (JsonElement) -> Unit)


