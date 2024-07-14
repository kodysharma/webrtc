package test.videocall

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import desidev.p2p.ICECandidate
import desidev.p2p.agent.P2PAgent
import desidev.p2p.turn.TurnConfiguration

private const val username = "test"
private const val password = "test123"
private const val TAG = "P2pAgentChat"

@Composable
fun P2PAgentChat(modifier: Modifier = Modifier) {
    var port by remember { mutableIntStateOf(-1) }
    val agent = remember {
        val tConfig = TurnConfiguration(
            username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
        )
        P2PAgent(P2PAgent.Config(turnConfig = tConfig))
    }

    DisposableEffect(agent) {
        agent.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                Log.d(TAG, "on ice created $ice")
                port = ice.find { it.type == ICECandidate.CandidateType.RELAY }?.port!!
            }

            override fun onError(e: Throwable) {
                Log.e(TAG, "", e)
            }
        })

        onDispose {
            agent.close()
        }
    }

    Scaffold(modifier) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            Text(
                text = "port: $port", modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }
    }
}