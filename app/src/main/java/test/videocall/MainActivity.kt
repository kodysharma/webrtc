package test.videocall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import desidev.videocall.service.bitmap.BitmapPool
import desidev.videocall.service.bitmap.BitmapWrapper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import desidev.videocall.service.yuv.YuvToRgbConverter

class MainActivity : ComponentActivity() {

    private val cameraWrapper by lazy { CameraWrapper(this) }

    private val bitmapImage = mutableStateOf<BitmapWrapper?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            LaunchedEffect(key1 = Unit) {
                cameraWrapper.startCamera()

                val encoderInput = Channel<BitmapWrapper>(4).apply {
                    launch {
                        for (bitmap in this@apply) {
                            // encode bitmap

                            // release after encode
                            bitmap.release()
                        }
                    }
                }

                val yuvToRgbConverter = YuvToRgbConverter(this@MainActivity)
                val bitmapPool = BitmapPool(cameraWrapper.imageReader.let {
                    Size(
                        it.width.toFloat(), it.height.toFloat()
                    )
                })

                cameraWrapper.imageReader.setOnImageAvailableListener(
                    { reader ->
                        cameraWrapper.handler.post {
                            val image = reader.acquireLatestImage()
                            val outputBitmap = bitmapPool.getBitmap()

                            // convert image to bitmap
                            yuvToRgbConverter.yuvToRgb(image, outputBitmap.bitmap)

                            bitmapImage.value = outputBitmap

                            launch {
                                encoderInput.trySend(outputBitmap)
                            }

                            image?.close()
                        }
                    },
                    cameraWrapper.handler
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                bitmapImage.value?.let {
                    Image(
                        bitmap = it.bitmap.asImageBitmap(),
                        contentDescription = "Camera preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(scaleX = -1f, scaleY = 1f)
                            .rotate(270f)
                    )
                }
            }
        }
    }
}