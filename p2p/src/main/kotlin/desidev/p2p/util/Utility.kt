package desidev.p2p.util

import desidev.p2p.turn.attribute.AddressValue
import desidev.p2p.turn.attribute.AttributeType
import desidev.p2p.turn.attribute.InetAF
import desidev.p2p.turn.message.TurnMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration


/**
 * Get ByteArray Hex representation string. for example 0x24f0 (2 digits for one byte value)
 */
fun ByteArray.toHexString(): String {
    val hex = map { String.format("%02x", it) }
    return StringBuilder().append("0x").apply { hex.forEach { append(it) } }.toString()
}


/**
 * Converts the hex string to byte array
 * for example if the hexString is 0x0101 then the output will
 * be [1, 1]. hexString should contain even numbers of digits.
 * As [ByteArray.toHexString] returns.
 */
fun String.decodeHexToByteArray(): ByteArray {
    val str = removePrefix("0x")
    val byteArray = ByteArray(str.length / 2) // I believe that string length would be an even number
    for (i in 0..str.lastIndex step 2) {
        val nibble = str.slice(i..i + 1)
        byteArray[i / 2] = nibble.toUByte(16).toByte()
    }

    return byteArray
}

fun decodeXorAddress(attributeValue: ByteArray): AddressValue {
    val family = attributeValue[1].toInt() and 0xFF
    val addressFamily = InetAF.values().find { it.code == family } ?: throw UnsupportedOperationException("Address family ($family) is not supported")
    val xorPort = ((attributeValue[2].toInt() and 0xFF) shl 8) or (attributeValue[3].toInt() and 0xFF)
    val magicCookie = TurnMessage.MAGIC_COCKIE

    val addressBytes = attributeValue.copyOfRange(4, 8)
    val xorAddress = ByteBuffer.wrap(addressBytes).int

    val address = xorAddress xor magicCookie
    val port = magicCookie shr 16 and 0xFF xor xorPort
    ByteBuffer.wrap(addressBytes).putInt(address)

    return AddressValue(addressFamily, addressBytes, port)
}

fun multipleOfFour(value: Int): Int {
    val r = value % 4
    if (r != 0) {
        val q = value / 4
        return q * 4 + 4
    }
    return value
}

fun generateHashCode(input: ByteArray, key: String): ByteArray {
    val md5 = MessageDigest.getInstance("MD5")
    val keyBytes = md5.digest(key.toByteArray())

    val hmac = Mac.getInstance("HmacSHA1")
    val secertKey = SecretKeySpec(keyBytes, "HmacSHA1")
    hmac.init(secertKey)
    return hmac.doFinal(input)
}


/**
 * If the given message contains a message-integrity attribute. Then it performs the message integrity check and returns
 * the result true if the message is unchanged and false otherwise. If the message does not contain a message-integrity attribute
 * then it returns false
 */
fun checkMessageIntegrity(message: TurnMessage, user: String, realm: String, password: String): Boolean {
    val hash = message.attributes.find{ it.type == AttributeType.MESSAGE_INTEGRITY.type }?.getValueAsByteArray()
    if (hash != null) {
        val msg = TurnMessage(
            message.header,
            message.attributes.dropLastWhile { it.type == AttributeType.MESSAGE_INTEGRITY.type })
        val target = generateHashCode(msg.encodeToByteArray(), "$user:$realm:$password")
        return hash.contentEquals(target)
    }
    return false
}


@OptIn(ObsoleteCoroutinesApi::class)
fun CoroutineScope.countdownTimer(
    duration: Duration,
    onTick: suspend (Long) -> Unit,
    onFinish: (suspend () -> Unit)? = null
): Job {
    check(duration.inWholeMilliseconds >= 0) { "Duration must be non-negative" }
    val tickerChannel = ticker(delayMillis = 1000)
    return launch {
        var timeRemaining = duration.inWholeMilliseconds
        while (isActive && timeRemaining > 0) {
            tickerChannel.receive()
            timeRemaining -= 1000
            onTick(timeRemaining)
        }
        tickerChannel.cancel()
        onFinish?.invoke()
    }
}
fun isReachable(host: String, openPort: Int, timeOutMillis: Int): Boolean
{
    try
    {
        Socket().use { soc ->
            soc.connect(InetSocketAddress(host, openPort), timeOutMillis)
        }
        return true
    }
    catch (ex: IOException)
    {
        return false
    }
}


fun preciseDelay(nanoseconds: Long) {
    val targetTime = System.nanoTime() + nanoseconds
    while (System.nanoTime() < targetTime) {
        // Busy-wait loop
    }
}