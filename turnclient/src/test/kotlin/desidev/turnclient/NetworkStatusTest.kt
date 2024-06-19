package desidev.turnclient

import kotlin.test.Test

class NetworkStatusTest
{
    private val networkStatus = NetworkStatus
    @Test
    fun network_Status_test()
    {
        networkStatus.addCallback(object : NetworkStatus.Callback
        {
            override fun onNetworkReachable()
            {
                println("On network reachable")
            }

            override fun onNetworkUnreachable()
            {
                println("On Network unreachable")
            }
        })

        Thread.sleep(10 * 60 * 1000)
        networkStatus.close()
    }
}