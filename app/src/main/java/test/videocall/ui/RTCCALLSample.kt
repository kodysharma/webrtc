package test.videocall.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import desidev.videocall.service.rtcclient.DefaultRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.R
import test.videocall.signalclient.AnswerEvent
import test.videocall.signalclient.OfferCancelledEvent
import test.videocall.signalclient.OfferEvent
import test.videocall.signalclient.Peer
import test.videocall.signalclient.PostAnswerParams
import test.videocall.signalclient.PostOfferParams
import test.videocall.signalclient.SessionClosedEvent
import test.videocall.signalclient.SignalClient

sealed class ScreenState {
    data object InitialState : ScreenState()
    data object ConnectedState : ScreenState()
    data class IncomingCallState(val offerEvent: OfferEvent) : ScreenState()
    data class OutgoingCallState(val peer: Peer) : ScreenState()
    data class InCallState(val peer: Peer) : ScreenState()
}

const val url = "ws://139.59.85.69:8080"
val screenState: MutableState<ScreenState> = mutableStateOf(ScreenState.InitialState)
val rtc = DefaultRtcClient("64.23.160.217", 3478, "test", "test123")
val signalClient = SignalClient()


private suspend fun processSignalEvents() {
    signalClient.eventFlow.collect { event ->
        when (event) {
            is OfferEvent -> {
                if (screenState.value == ScreenState.ConnectedState) {
                    screenState.value = ScreenState.IncomingCallState(event)
                }
            }

            is AnswerEvent -> {
                if (screenState.value is ScreenState.OutgoingCallState) {
                    if (event.accepted) {
                        rtc.setRemoteIce(event.candidates!!)
                        screenState.value = ScreenState.InCallState(event.sender)
                    } else {
                        screenState.value = ScreenState.ConnectedState
                    }
                }
            }

            is OfferCancelledEvent -> {
                if (screenState.value is ScreenState.IncomingCallState) {
                    screenState.value = ScreenState.ConnectedState
                }
            }

            is SessionClosedEvent -> {
                if (screenState.value is ScreenState.IncomingCallState) {
                    screenState.value = ScreenState.ConnectedState
                    rtc.closePeerConnection()
                }
            }
        }
    }
}

private suspend fun callPeer(peer: Peer) {
    rtc.createLocalCandidate()
    signalClient.postOffer(
        PostOfferParams(
            receiverId = peer.id,
            candidates = rtc.getLocalIce()
        )
    )

    screenState.value = ScreenState.OutgoingCallState(peer)
}


@Composable
fun RTCCAllSample() {
    val scope = rememberCoroutineScope()
    var peerName by remember { mutableStateOf("") }

    Surface {
        AnimatedContent(
            targetState = screenState.value,
            label = "Screen State"
        ) { state: ScreenState ->
            when (state) {
                is ScreenState.InitialState -> {
                    InitialScreen(peerName = peerName,
                        onPeerNameChanged = { peerName = it },
                        onConnectClicked = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    signalClient.connect("$url/$peerName")
                                    screenState.value = ScreenState.ConnectedState
                                }
                                processSignalEvents()
                            }
                        })
                }

                is ScreenState.ConnectedState -> {
                    ConnectedScreen(
                        peerLoader = { signalClient.getPeers() },
                        onCallToPeer = {
                            scope.launch(Dispatchers.IO) { callPeer(it) }
                        }
                    )
                }

                is ScreenState.OutgoingCallState -> {
                    OutGoingCallScreen(receiverPeer = state.peer)
                }

                is ScreenState.IncomingCallState -> {
                    IncomingCallScreen(offerEvent = state.offerEvent)
                }

                is ScreenState.InCallState -> {
                    InCallScreen(remotePeer = state.peer)
                }
            }
        }
    }
}


@Composable
fun InitialScreen(
    peerName: String, onPeerNameChanged: (String) -> Unit, onConnectClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        OutlinedTextField(value = peerName,
            onValueChange = onPeerNameChanged,
            label = { Text("Peer Name") })

        Button(onClick = onConnectClicked) {
            Text("Connect")
        }
    }
}


@Composable
fun ConnectedScreen(
    peerLoader: suspend () -> List<Peer>,
    onCallToPeer: (Peer) -> Unit,
) {
    var peers by remember { mutableStateOf(listOf<Peer>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        peers = peerLoader()
    }


    @Composable
    fun PeerItem(modifier: Modifier, peer: Peer, onPeerClicked: () -> Unit) {
        Card(
            onClick = onPeerClicked,
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = if (peer.status == Peer.Status.Active) colorScheme.primary else colorScheme.secondary)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "name: ${peer.name}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "id: ${peer.id}", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(text = peer.status.name)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = CenterHorizontally
        ) {
            item {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                )
                {
                    FilledIconButton(
                        onClick = { scope.launch { peers = peerLoader() } },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_refresh_24),
                            contentDescription = "refresh"
                        )
                    }
                }
            }

            items(peers) { peer ->
                PeerItem(peer = peer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    onPeerClicked = {
                        if (peer.status == Peer.Status.Active) {
                            onCallToPeer(peer)
                        }
                    })
            }
        }
    }
}

@Composable
fun OutGoingCallScreen(receiverPeer: Peer) {
    Surface(modifier = Modifier.fillMaxSize()) {
        ConstraintLayout {
            val name = createRef()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.constrainAs(name) {
                    centerHorizontallyTo(parent)
                    top.linkTo(parent.top, 40.dp)
                }
            ) {
                Text(
                    text = "Calling to",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(text = receiverPeer.name, style = MaterialTheme.typography.labelLarge)
            }

            val buttonEndCall = createRef()
            IconButton(
                onClick = {
                    screenState.value = ScreenState.ConnectedState
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Red,
                    contentColor = Color.White
                ),
                modifier = Modifier.constrainAs(buttonEndCall) {
                    centerHorizontallyTo(parent)
                    bottom.linkTo(parent.bottom, 40.dp)
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_call_end_24),
                    contentDescription = "End call"
                )
            }
        }
    }
}

@Composable
fun IncomingCallScreen(offerEvent: OfferEvent) {
    val scope = rememberCoroutineScope()
    ConstraintLayout(modifier = Modifier.fillMaxSize()) {
        val name = createRef()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.constrainAs(name) {
                centerHorizontallyTo(parent)
                top.linkTo(parent.top, 40.dp)
            }
        ) {
            Text(text = "Incoming call from", style = MaterialTheme.typography.headlineMedium)
            Text(text = offerEvent.sender.name, style = MaterialTheme.typography.labelLarge)
        }

        val answerButtons = createRef()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            modifier = Modifier.constrainAs(answerButtons) {
                centerHorizontallyTo(parent)
                bottom.linkTo(parent.bottom, 40.dp)
            }
        ) {
            // accept icon button
            IconButton(
                onClick = {
                    scope.launch {
                        rtc.createLocalCandidate()
                        rtc.setRemoteIce(offerEvent.candidates)
                        rtc.createPeerConnection()
                        signalClient.postAnswer(
                            PostAnswerParams(
                                receiverId = offerEvent.sender.id,
                                accepted = true,
                                candidates = rtc.getLocalIce()
                            )
                        )
                        screenState.value = ScreenState.InCallState(offerEvent.sender)
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Green,
                    contentColor = Color.White
                ),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_call_24),
                    contentDescription = "Accept call"
                )
            }

            // decline icon button
            IconButton(
                onClick = {
                    scope.launch {
                        rtc.createLocalCandidate()
                        rtc.setRemoteIce(offerEvent.candidates)
                        signalClient.postAnswer(
                            PostAnswerParams(
                                receiverId = offerEvent.sender.id,
                                accepted = false,
                                candidates = null
                            )
                        )
                        screenState.value = ScreenState.ConnectedState
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Red,
                    contentColor = Color.White
                ),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_call_end_24),
                    contentDescription = "Accept call"
                )
            }
        }
    }
}


@Composable
fun InCallScreen(remotePeer: Peer) {
    val scope = rememberCoroutineScope()


    DisposableEffect(Unit) {
        scope.launch {
            rtc.createPeerConnection()
            rtc.startSendingMessage()
        }
        onDispose {
            scope.launch {
                rtc.closePeerConnection()
            }
        }
    }

    ConstraintLayout(modifier = Modifier.fillMaxSize()) {
        val withPeer = createRef()
        Text(
            text = "Call with ${remotePeer.name}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.constrainAs(withPeer) {
                centerHorizontallyTo(parent)
                top.linkTo(parent.top, 40.dp)
            }
        )

        val closeButton = createRef()
        IconButton(onClick = {
            scope.launch {
                signalClient.postCloseSession()
                screenState.value = ScreenState.ConnectedState
            }
        }, modifier = Modifier.constrainAs(closeButton) {
            bottom.linkTo(parent.bottom, 16.dp)
            end.linkTo(parent.end, 16.dp)
        }) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
            )
        }
    }
}
