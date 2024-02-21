# AudioEncoder

In this module there is an interface for audio encoders.
The interface is generic and has a type parameter `Sink`
which provides the output data type depending on the implementation class.

```kotlin
interface AudioEncoder<out Sink : Any> {
    val encodedBuffer: Sink
    fun configure(format: AudioFormat)
    fun startEncoder()
    fun stopEncoder()
    fun enqueRawBuffer(buffer: AudioBuffer)
    fun release()

    fun mediaFormat(): Future<MediaFormat>
}
```

## State of the encoder

Initially, the encoder is in the `UNCONFIGURED` state.

## Default audio encoder

AudioEncoder.defaultAudioEncoder() - returns an instance of `AudioEncoder` with default
implementation.

```kotlin
// create an instance of AudioEncoder with default implementation
val audioEncoder = AudioEncoder.defaultAudioEncoder()
```

**AudioRecordingSample.kt** - example of using `AudioEncoder` with default implementation.
