package com.vaultsync.core.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(context: Context): String? {
        try {
            // First attempt: WifiManager DHCP/connection info
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress
            if (wifiIp != null && wifiIp != 0) {
                val ip = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff,
                )
                if (isPrivateSubnetAddress(ip)) {
                    return ip
                }
            }

            // Second attempt: NetworkInterface iteration
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && isPrivateSubnetAddress(host)) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getWifiSsid(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                return ssid.removeSurrounding("\"")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun normalizeIp(ipOrHost: String): String {
        var host = ipOrHost
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()

        // Handle bracketed IPv6 with port: [::1]:8080 or [2409:...]:8080
        if (host.startsWith('[') && host.contains(']')) {
            host = host.substringAfter('[').substringBefore(']')
        } else {
            // If IPv4 with port, e.g. 192.168.1.1:8080 (single colon)
            // IPv6 addresses contain 2 or more colons (or start with ::)
            val colonCount = host.count { it == ':' }
            if (colonCount == 1) {
                host = host.substringBefore(':')
            }
        }

        // Strip zone/scope ID: e.g. fe80::1%wlan0 or fe80::1%17
        if (host.contains('%')) {
            host = host.substringBefore('%')
        }

        // Strip IPv4-mapped IPv6 prefix: ::ffff:192.168.1.1 or ::FFFF:192.168.1.1
        if (host.startsWith("::ffff:", ignoreCase = true)) {
            host = host.substring(7)
        } else if (host.startsWith("0:0:0:0:0:ffff:", ignoreCase = true)) {
            host = host.substring(15)
        }

        return host.trim()
    }

    fun isPrivateSubnetAddress(ipOrHost: String): Boolean {
        val host = normalizeIp(ipOrHost)

        // 1. Loopback
        if (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") {
            return true
        }

        // 2. IPv6 link-local and unique local (ULA)
        if (host.startsWith("fe80", ignoreCase = true) ||
            host.startsWith("fc", ignoreCase = true) ||
            host.startsWith("fd", ignoreCase = true)) {
            return true
        }

        // 3. IPv4 private, CGNAT, and link-local ranges
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            val (a, b, _, _) = parts
            val isPrivateIpv4 = when {
                // 10.0.0.0 – 10.255.255.255 (RFC 1918)
                a == 10 -> true
                // 172.16.0.0 – 172.31.255.255 (RFC 1918)
                a == 172 && b in 16..31 -> true
                // 192.168.0.0 – 192.168.255.255 (RFC 1918)
                a == 192 && b == 168 -> true
                // 169.254.0.0 – 169.254.255.255 (RFC 3927 Link-local / APIPA)
                a == 169 && b == 254 -> true
                // 100.64.0.0 – 100.127.255.255 (RFC 6598 Shared Address / Mobile Hotspot)
                a == 100 && b in 64..127 -> true
                else -> false
            }
            if (isPrivateIpv4) return true
        }

        // 4. Same local network subnet check (for Global IPv6 / custom LANs on same Wi-Fi)
        try {
            val targetAddr = InetAddress.getByName(host)
            if (targetAddr.isLoopbackAddress || targetAddr.isSiteLocalAddress || targetAddr.isLinkLocalAddress) {
                return true
            }

            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                // Exclude cellular data interfaces so external WAN traffic cannot match cellular IP
                val name = intf.name.lowercase()
                if (name.startsWith("ccmni") || name.startsWith("rmnet") || name.startsWith("pdp") || name.startsWith("radio")) {
                    continue
                }

                for (intfAddr in intf.interfaceAddresses) {
                    val localAddr = intfAddr.address
                    val prefixLen = intfAddr.networkPrefixLength.toInt()
                    if (prefixLen <= 0) continue

                    if (localAddr.javaClass == targetAddr.javaClass) {
                        if (matchesSubnet(localAddr.address, targetAddr.address, prefixLen)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore name resolution failure
        }

        return false
    }

    fun matchesSubnet(a: ByteArray, b: ByteArray, prefixLengthBits: Int): Boolean {
        if (a.size != b.size) return false
        val fullBytes = prefixLengthBits / 8
        val remainingBits = prefixLengthBits % 8

        for (i in 0 until fullBytes) {
            if (i >= a.size) break
            if (a[i] != b[i]) return false
        }

        if (remainingBits > 0 && fullBytes < a.size) {
            val mask = ((0xFF shl (8 - remainingBits)) and 0xFF).toByte()
            if ((a[fullBytes].toInt() and mask.toInt()) != (b[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }
        return true
    }
}
