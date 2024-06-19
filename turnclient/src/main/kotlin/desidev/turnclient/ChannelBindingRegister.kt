package desidev.turnclient

import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

class ChannelBindingRegister
{
    private val channelToBinding = mutableMapOf<Int, ChannelBinding>()
    private val peerToBinding = mutableMapOf<InetSocketAddress, ChannelBinding>()

    /**
     * Binds a channel number with a peer address.
     */
    fun registerChannel(channelBinding: ChannelBinding)
    {
        peerToBinding[channelBinding.peer] = channelBinding
    }


    fun remove(channelNumber: Int)
    {
        channelToBinding.remove(channelNumber)?.let {
            peerToBinding.remove(it.peer)
        }
    }

    fun remove(peer: InetSocketAddress)
    {
        peerToBinding.remove(peer)?.let {
            channelToBinding.remove(it.channelNumber)
        }
    }

    fun contains(peer: InetSocketAddress) = peerToBinding.containsKey(peer)
    fun contains(channelNumber: Int) = channelToBinding.containsKey(channelNumber)

    /**
     * Retrieves the channel number associated with the given peer address.
     */
    fun getChannel(peer: InetSocketAddress): Int?
    {
        return peerToBinding[peer]?.channelNumber
    }

    /**
     * Retrieves the peer address associated with the given channel number.
     */
    fun getPeerAddress(channelNumber: Int): InetSocketAddress?
    {
        return channelToBinding[channelNumber]?.peer
    }

    fun clear()
    {
        peerToBinding.clear()
        channelToBinding.clear()
    }

    fun toList() = peerToBinding.toList().map { it.second }
    fun get(peer: InetSocketAddress): ChannelBinding?
    {
        return peerToBinding[peer]
    }
}


data class ChannelBinding(
    val peer: InetSocketAddress,
    val channelNumber: Int,
    var enable: Boolean,
    val lifetimeSec: Int
) : ExpireTimer by ExpireTimerImpl(lifetimeSec.seconds)