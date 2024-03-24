package test.videocall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import test.videocall.ui.CameraToVideoPlayer
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            exitProcess(1)
        }

        enableEdgeToEdge()
        setContent {
            Scaffold {
                Box(modifier = Modifier.padding(it)) {
                    CameraToVideoPlayer()
//                    RTCCAllSample()
                }
            }
        }
    }
}


