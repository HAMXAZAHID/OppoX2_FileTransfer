package dev.hamza.watchfiles.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.hamza.watchfiles.smb.SmbServer
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saves servers in app preferences. Passwords are encrypted with a key kept in the Android Keystore. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<SmbServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SmbServer(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    host = o.getString("host"),
                    share = o.getString("share"),
                    port = o.optInt("port", 445),
                    username = o.optString("username"),
                    password = o.optString("password").takeIf { it.isNotEmpty() }?.let { decrypt(it) }.orEmpty(),
                    domain = o.optString("domain"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(servers: List<SmbServer>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("share", s.share)
                    .put("port", s.port)
                    .put("username", s.username)
                    .put("password", if (s.password.isEmpty()) "" else encrypt(s.password))
                    .put("domain", s.domain)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds a server, or replaces the one with the same host and share. Returns the new list. */
    fun upsert(server: SmbServer): List<SmbServer> {
        val existing = load()
        val old = existing.firstOrNull { it.host.equals(server.host, true) && it.share.equals(server.share, true) }
        val s = server.copy(id = old?.id ?: server.id.ifEmpty { java.util.UUID.randomUUID().toString() })
        val merged = existing.filterNot { it.id == s.id } + s
        save(merged)
        return merged
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = runCatching {
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEY = "servers_v1"
        private const val ALIAS = "watchfiles_passwords"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
