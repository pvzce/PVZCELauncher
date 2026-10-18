package com.tungsten.fcl.generic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, non-backup, encrypted website sessions. The API origin is authenticated as AAD. */
class WorkshopSessionStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "pvzce_sessions")

    data class Session(val cookie: String, val csrf: String, val expires: String, val account: JsonObject) {
        fun expired() = !Instant.parse(expires).isAfter(Instant.now())
    }

    private fun key(): SecretKey = synchronized(WorkshopSessionStore::class.java) {
        val alias = "pvzce.website.sessions.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(api: String): Session? {
        val file = File(root, WorkshopApi.key(api))
        if (!file.exists()) return null
        check(file.length() <= 65536) { "账号记录过大" }
        val encoded = file.readBytes()
        check(encoded.size > 12) { "账号记录损坏，请清除本机登录后重试" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
        cipher.updateAAD(WorkshopApi.normalize(api).toByteArray(Charsets.UTF_8))
        return WorkshopApi.JSON.fromJson(String(cipher.doFinal(encoded.copyOfRange(12, encoded.size)), Charsets.UTF_8), Session::class.java)
    }

    fun save(api: String, cookie: String, response: JsonObject): Session {
        WorkshopApi(api, cookie) // Enforce the native cookie header grammar.
        val session = Session(cookie, response.get("csrf_token").asString,
            response.get("expires_at").asString, response.getAsJsonObject("user").deepCopy())
        check(!session.expired() && session.csrf.isNotEmpty()) { "服务器返回的会话无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(WorkshopApi.normalize(api).toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(WorkshopApi.JSON.toJson(session).toByteArray(Charsets.UTF_8))
        InstanceFiles.write(File(root, WorkshopApi.key(api)), cipher.iv + encrypted)
        return session
    }

    fun clear(api: String) { java.nio.file.Files.deleteIfExists(File(root, WorkshopApi.key(api)).toPath()) }
}
