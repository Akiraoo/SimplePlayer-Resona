package com.akira.simpleplayer.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 (API 37) local network protection: an app targeting 37 needs the
 * ACCESS_LOCAL_NETWORK runtime permission to reach LAN addresses (192.168.x.x and so on).
 * Without it TCP connections simply time out, so the sync, the streams and the covers
 * would all fail with no clear reason. Servers on public addresses are not affected, so
 * the permission is asked for only when the configured server is on the local network.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Devices before Android 17 never block local traffic. */
    val enforced: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(ctx: Context): Boolean =
        !enforced || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when the server needs the permission and the app does not have it. Blocking (DNS). */
    fun blocked(ctx: Context, server: String): Boolean =
        enforced && !granted(ctx) && isLocal(server)

    /**
     * Does [server] ("192.168.0.10:55555", "http://nuc.local:55555/", "example.com") point at
     * the local network? Names are resolved; one local address is enough.
     */
    fun isLocal(server: String): Boolean {
        val host = hostOf(server) ?: return false
        val lower = host.lowercase()
        if (lower == "localhost") return false // loopback is never gated
        // mDNS names live on the local network by definition (and may not resolve yet)
        if (lower.endsWith(".local")) return true
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        return addrs.any { isLocalAddress(it) }
    }

    /** Address ranges Android 17 counts as local network (see "Local Network Definition"). */
    fun isLocalAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress) return false
        if (a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xff
                val o1 = b[1].toInt() and 0xff
                (o0 == 100 && o1 in 64..127) ||          // 100.64.0.0/10 (CGNAT)
                    (o0 == 255 && b.all { it == (-1).toByte() }) // broadcast
            }
            // fc00::/7 unique local (directly connected home IPv6 networks)
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    /** Host part of what the user typed in settings; null if there is none. */
    fun hostOf(server: String): String? {
        var s = server.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val host = runCatching { Uri.parse(s).host }.getOrNull()
        return host?.trim('[', ']')?.takeIf { it.isNotEmpty() }
    }

    /** After a "don't ask again" denial only the app's settings page can grant it. */
    fun canAskAgain(activity: Activity): Boolean =
        activity.shouldShowRequestPermissionRationale(PERMISSION)

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
