package test.videocall.ui

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec.BufferInfo
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import desidev.rtc.media.ReceivingPort
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.R
import test.videocall.RTCPhone
import test.videocall.signalclient.Peer


@Composable
fun RTCCAllSample() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var peerName by remember { mutableStateOf("") }
    val rtcPhone = remember { RTCPhone(context) }
    val phoneState by rtcPhone.phoneStateFlow.collectAsState()
    val cameraEnable by rtcPhone.cameraStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        rtcPhone.phoneStateFlow.collect {
            if (it is RTCPhone.State.InSession || it is RTCPhone.State.OutgoingCall || it is RTCPhone.State.IncomingCall) {
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

    Surface {
        AnimatedContent(
            targetState = phoneState,
            label = "Screen State"
        ) { state ->
            when (state) {
                RTCPhone.State.OnLine -> {
                    ConnectedScreen(
                        peerLoader = {
                            withContext(Dispatchers.IO) {
                                rtcPhone.getPeers()
                            }
                        },
                        onCallToPeer = { peer ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    rtcPhone.makeCall(peer)
                                }
                            }
                        }
                    )
                }

                is RTCPhone.State.OutgoingCall -> {
                    OutGoingCallScreen(
                        receiverPeer = state.receiver,
                        onEndCallClicked = { scope.launch { withContext(Dispatchers.IO) { rtcPhone.endCall() } } },
                        rtcPhone = rtcPhone
                    )
                }

                is RTCPhone.State.IncomingCall -> {
                    IncomingCallScreen(
                        sender = state.sender,
                        onAcceptClicked = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    rtcPhone.acceptCall(state.sender)
                                }
                            }
                        },
                        onRejectClicked = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    rtcPhone.rejectCall(state.sender)
                                }
                            }
                        }
                    )
                }

                is RTCPhone.State.InSession -> {
                    InCallScreen(remotePeer = state.peer, rtcPhone = rtcPhone)
                }

                RTCPhone.State.OffLine -> {
                    OfflineScreen(
                        peerName = peerName,
                        onPeerNameChanged = { peerName = it },
                        onConnectClicked = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    rtcPhone.goOnline(peerName)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}


@Composable
fun OfflineScreen(
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

@Composable
fun OutGoingCallScreen(
    receiverPeer: Peer,
    onEndCallClicked: () -> Unit,
    rtcPhone: RTCPhone
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        ConstraintLayout {
            val localPeerView = createRef()
            rtcPhone.LocalPeerView(modifier = Modifier.fillMaxSize().constrainAs(localPeerView) {
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
    val TAG = "InCallScreen"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cameraCapture = remember { CameraCaptureImpl(context) }
    val currentPreviewFrame = remember { mutableStateOf<ImageBitmap?>(null) }
    val yuvToRgbConverter = remember { desidev.utility.yuv.YuvToRgbConverter(context) }

    fun Image.toBitmap(): Bitmap {
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        yuvToRgbConverter.yuvToRgb(this, outputBitmap)
        return outputBitmap
    }


    ConstraintLayout(modifier = Modifier.fillMaxSize()) {
        val localPeerView = createRef()
        rtcPhone.LocalPeerView(modifier = Modifier.fillMaxSize().constrainAs(localPeerView) {
            centerHorizontallyTo(parent)
            bottom.linkTo(parent.bottom, 16.dp)
        })

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
                withContext(Dispatchers.IO) {
                    rtcPhone.endCall()
                }
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


private fun CoroutineScope.receivingPortToChannel(port: ReceivingPort<Pair<ByteArray, BufferInfo>>): Channel<Sample> {
    val channel = Channel<Sample>(Channel.BUFFERED)
    launch {
        while (port.isOpenForReceive) {
            try {
                channel.send(port.receive().run {
                    Sample(
                        timeStamp = second.presentationTimeUs,
                        sample = first,
                        flag = second.flags
                    )
                })
            } catch (_: Exception) {
            }
        }
        Log.d("RTCCallSample", "sample channel closed")
        channel.close()
    }
    return channel
}



