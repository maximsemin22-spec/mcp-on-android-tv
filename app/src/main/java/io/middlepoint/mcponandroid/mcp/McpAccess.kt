package io.middlepoint.mcponandroid.mcp

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local-network MCP access key.
 * The secret is generated on-device, never committed to source control.
 */
object McpAccess {
    private const val PREFS = "tv_mcp_security"
    private const val KEY = "mcp_access_key"

    @Synchronized
    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.length == 32 }?.let { return it }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString(KEY, generated).commit()
        return generated
    }

    fun accepts(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            token(context).toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    fun url(context: Context, ip: String): String =
        "http://$ip:8082/mcp?key=${token(context)}"
}
