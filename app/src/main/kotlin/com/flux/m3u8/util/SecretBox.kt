package com.flux.m3u8.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 AndroidKeyStore 的 AES-256-GCM 加密少量敏感字符串（目前只有任务的 Cookie）。
 *
 * 为什么需要：Cookie 里往往带着登录态，之前它以明文躺在 SQLite 里，
 * 而 `allowBackup` 又是开的——任何能拿到备份的人都能直接读出登录凭证。
 *
 * 设计取舍：
 *  · 密钥存在系统 KeyStore，**不进备份、不可导出**，由 TEE/StrongBox 保护
 *  · 失败一律**降级为明文**而不是抛异常：解密失败最多是"要重新登录"，
 *    绝不能因为加密组件不可用就让整个下载功能挂掉
 *  · 密文带 `enc1:` 前缀，读到没有前缀的值就当历史明文返回，平滑兼容老数据
 */
object SecretBox {

    private const val ALIAS = "flux_task_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var unavailable = false

    /** 加密；失败或无需加密时原样返回。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return plain
        val key = key() ?: return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    /** 解密；非密文（历史明文）原样返回，解密失败返回空串。 */
    fun decrypt(stored: String): String {
        if (stored.isBlank()) return stored
        if (!stored.startsWith(PREFIX)) return stored      // 老数据，明文
        val key = key() ?: return ""
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.DEFAULT)
            if (raw.size <= IV_LEN) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN)
            )
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun key(): SecretKey? {
        if (unavailable) return null
        cachedKey?.let { return it }
        return synchronized(this) {
            if (unavailable) return@synchronized null
            try {
                cachedKey?.let { return@synchronized it }
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existing = ks.getKey(ALIAS, null) as? SecretKey
                if (existing != null) {
                    cachedKey = existing
                    existing
                } else {
                    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    gen.init(
                        KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    val created = gen.generateKey()
                    cachedKey = created
                    created
                }
            } catch (e: Exception) {
                // 个别 ROM 的 KeyStore 实现有问题，索性永久降级为明文，
                // 避免每次调用都重新抛异常拖慢数据库读写
                unavailable = true
                null
            }
        }
    }
}
