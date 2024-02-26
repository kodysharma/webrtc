package test.videocall.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import desidev.videocall.service.VideoCallService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun CallSample(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val callService : VideoCallService<Any>? = null

    fun call() {
        scope.launch {
            withTimeout(5000L) {
                callService?.call(offer = TODO())
            }
        }
    }

    LaunchedEffect(key1 =callService) {
        callService?.incomingCall?.collect {

        }
    }
}