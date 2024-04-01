package test.videocall.ui

import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import test.videocall.R
import test.videocall.RTCPhone
import test.videocall.RTCPhone.CallState
import test.videocall.RTCPhoneAction
import test.videocall.signalclient.Peer


private const val TAG = "RtcPhoneSample"

sealed class Screen {
    data object Home : Screen()
    data object Users : Screen()
    data object CallScreen : Screen()
}

@Composable
fun RTCCAllSample() {
    val context = LocalContext.current
    var peerName by remember { mutableStateOf("") }
    val rtcPhone = remember { RTCPhone(context) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(Unit) {
        rtcPhone.callStateFlow.collect {
            if (it is CallState.InSession || it is CallState.CallingToPeer || it is CallState.IncomingCall) {
                if (!rtcPhone.cameraStateFlow.value) {
                    rtcPhone.enableCamera()
                }
            } else {
                if (rtcPhone.cameraStateFlow.value) {
                    rtcPhone.disableCamera()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        rtcPhone.errorSharedFlow.collect {
            Log.e(TAG, it.message, it.cause)
        }
    }
    LaunchedEffect(Unit) {
        launch {
            rtcPhone.connectionStateFlow.collect {
                currentScreen = when (it) {
                    RTCPhone.ConnectionState.Connected -> {
                        Screen.Users
                    }

                    RTCPhone.ConnectionState.DisConnected -> {
                        Screen.Home
                    }
                }
            }
        }
        launch {
            rtcPhone.callStateFlow.filter { it !is CallState.NoSession }.collect {
                if (rtcPhone.connectionStateFlow.value == RTCPhone.ConnectionState.Connected)
                    currentScreen = Screen.CallScreen
            }
        }
    }


    AnimatedContent(targetState = currentScreen, label = "") { screen ->
        when (screen) {
            is Screen.Home -> {
                HomeScreen(
                    peerName = peerName,
                    onPeerNameChanged = { peerName = it },
                    onConnectClicked = {
                        rtcPhone.trySend(
                            RTCPhoneAction.GoOnline(
                                peerName, onSuccess = { }, onFailure = { }
                            )
                        )
                    })
            }

            is Screen.Users -> {
                UsersScreen(
                    peerLoader = { rtcPhone.getPeers() },
                    onCallToPeer = { peer ->
                        rtcPhone.trySend(
                            RTCPhoneAction.MakeCall(
                                peer,
                                onSuccess = {},
                                onFailure = {},
                            )
                        )
                    }
                )
            }

            is Screen.CallScreen -> {
                CallScreen(rtcPhone = rtcPhone, onNavigateBack = {
                    currentScreen = Screen.Users
                })
            }
        }

    }
}


@Composable
fun HomeScreen(
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
fun UsersScreen(
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
                    Text(
                        text = "name: ${peer.name}",
                        style = MaterialTheme.typography.bodyLarge
                    )
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

sealed class CallScreenState {
    data class OutGoingCall(val receiver: Peer) : CallScreenState()
    data class IncomingCall(val sender: Peer) : CallScreenState()
    data class InCall(val peer: Peer) : CallScreenState()
}

private fun callScreenState(rtcPhone: RTCPhone): CallScreenState =
    when (val callState = rtcPhone.callStateFlow.value) {
        is CallState.IncomingCall -> CallScreenState.IncomingCall(callState.sender)
        is CallState.CallingToPeer -> CallScreenState.OutGoingCall(callState.receiver)
        is CallState.InSession -> CallScreenState.InCall(callState.peer)
        else -> throw UnsupportedOperationException("Cannot convert call state to CallScreenState: $callState")
    }

@Composable
fun CallScreen(rtcPhone: RTCPhone, onNavigateBack: () -> Unit) {
    var callScreenState by remember { mutableStateOf(callScreenState(rtcPhone)) }

    LaunchedEffect(Unit) {
        rtcPhone.callStateFlow.filter { it is CallState.NoSession }
            .collect { onNavigateBack() }
    }

    LaunchedEffect(Unit) {
        rtcPhone.callStateFlow.filter { it !is CallState.NoSession }.collect {
            callScreenState = callScreenState(rtcPhone)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(targetState = callScreenState, label = "") { state ->
            when (state) {
                is CallScreenState.InCall -> InCallScreen(
                    remotePeer = state.peer,
                    rtcPhone = rtcPhone
                )

                is CallScreenState.IncomingCall -> IncomingCallScreen(
                    sender = state.sender,
                    onAcceptClicked = { rtcPhone.trySend(RTCPhoneAction.PickUpCall) },
                    onRejectClicked = { rtcPhone.trySend(RTCPhoneAction.EndCall) }
                )

                is CallScreenState.OutGoingCall -> OutGoingCallScreen(
                    receiverPeer = state.receiver,
                    onEndCallClicked = { rtcPhone.trySend(RTCPhoneAction.EndCall) },
                    rtcPhone = rtcPhone
                )
            }
        }
    }
}

@Composable
fun OutGoingCallScreen(
    receiverPeer: Peer,
    onEndCallClicked: () -> Unit,
    rtcPhone: RTCPhone
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        ConstraintLayout {
            val localPeerView = createRef()
            rtcPhone.LocalPeerView(modifier = Modifier
                .fillMaxSize()
                .constrainAs(localPeerView) {
                    centerHorizontallyTo(parent)
                    bottom.linkTo(parent.bottom, 16.dp)
                })

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
                onClick = onEndCallClicked,
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
fun IncomingCallScreen(sender: Peer, onRejectClicked: () -> Unit, onAcceptClicked: () -> Unit) {
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
            Text(text = sender.name, style = MaterialTheme.typography.labelLarge)
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
                onClick = onAcceptClicked,
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
                onClick = onRejectClicked,
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
fun InCallScreen(remotePeer: Peer, rtcPhone: RTCPhone) {
    ConstraintLayout(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            rtcPhone.LocalPeerView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            rtcPhone.RemotePeerView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }


        val withPeer = createRef()
        Text(
            text = "Call with ${remotePeer.name}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .constrainAs(withPeer) {
                    start.linkTo(parent.start)
                    top.linkTo(parent.top)
                }
                .padding(16.dp)
        )

        val closeButton = createRef()
        IconButton(
            onClick = { rtcPhone.trySend(RTCPhoneAction.EndCall) },
            modifier = Modifier.constrainAs(closeButton) {
                bottom.linkTo(parent.bottom, 16.dp)
                end.linkTo(parent.end, 16.dp)
            }
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
            )
        }
    }
}


