package com.kidsmonitor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.Locale

object NetworkUtils {
    fun getLocalIpAddress(context: Context): String? {
        // FIX: WifiManager.connectionInfo is deprecated in API 31 and returns incomplete
        // data on Android 12+. Use ConnectivityManager + LinkProperties instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val connectivityManager =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
            return null
        } else {
            // Fallback for API < 23
            @Suppress("DEPRECATION")
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) return null
            return String.format(
                Locale.getDefault(),
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        }
    }
}
