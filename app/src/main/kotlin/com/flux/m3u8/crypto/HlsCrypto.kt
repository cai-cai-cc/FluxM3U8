package com.flux.m3u8.crypto

import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * HLS 分片解密（AES-128-CBC）。
 *
 * 使用 Android 自带的 javax.crypto，不需要任何第三方库，
 * 也不需要像 famd 那样额外打一个 aria2/ffmpeg 二进制进 APK。
 */
object HlsCrypto {

    private const val TS_PACKET = 188
    private const val BUFFER = 64 * 1024
    private val HEX = setOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f', 'A', 'B', 'C', 'D', 'E', 'F'
    )

    /**
     * 把站点返回的密钥统一成合法长度。
     *
     * 现实中会遇到两种写法：标准 16 字节二进制，或 32 字符的 hex 文本
     * （不少自建切片脚本和 CDN 这么干）。这里两种都兼容。
     */
    fun normalizeKey(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) throw IllegalArgumentException("密钥内容为空")

        if (raw.size in setOf(16, 24, 32)) {
            val text = String(raw, Charsets.US_ASCII).trim().trim('"', '\'')
            if (text.length in setOf(32, 48, 64) && text.all { it in HEX }) {
                return hexToBytes(text)
            }
            return raw
        }

        val text = String(raw, Charsets.UTF_8).trim().trim('"', '\'')
        if (text.length in setOf(32, 48, 64) && text.all { it in HEX }) {
            return hexToBytes(text)
        }

        if (raw.size > 16) return raw.copyOf(16)
        throw IllegalArgumentException("密钥长度异常：${raw.size} 字节")
    }

    /** 把 EXT-X-KEY 的 IV 属性（0x...）转成 16 字节。 */
    fun ivFromHex(ivHex: String): ByteArray {
        var s = ivHex.trim()
        if (s.startsWith("0x", true)) s = s.substring(2)
        val b = hexToBytes(s)
        return when {
            b.size >= 16 -> b.copyOf(16)
            b.isEmpty() -> ByteArray(16)
            else -> ByteArray(16).also { b.copyInto(it, 16 - b.size) }  // 左侧补零
        }
    }

    /** 未显式指定 IV 时，使用 media sequence 的大端 16 字节表示。 */
    fun ivFromSequence(sequence: Long): ByteArray =
        ByteArray(16).also {
            var v = sequence
            for (i in 15 downTo 0) {
                it[i] = (v and 0xFF).toByte()
                v = v shr 8
            }
        }

    /** AES-128-CBC 解密 + 去除补齐的填充。 */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = cipher.doFinal(data)
        return stripPadding(out)
    }

    /**
     * 去掉加密时补上的填充。
     *
     * 加密端会把分片补齐到 16 的整数倍，解密后必须还原，
     * 否则文件尾部会多出垃圾字节，导致播放器解不出流。
     * MPEG-TS 包固定 188 字节，因此用"能否被 188 整除"作为校验依据。
     */
    private fun stripPadding(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % TS_PACKET == 0) return data

        val pad = data.last().toInt() and 0xFF
        if (pad in 1..16 && data.size >= pad) {
            var allSame = true
            for (i in data.size - pad until data.size) {
                if ((data[i].toInt() and 0xFF) != pad) { allSame = false; break }
            }
            if (allSame) return data.copyOf(data.size - pad)
        }
        return data
    }

    /**
     * 流式解密：源文件 → 目标文件。
     *
     * 相比 [decrypt]（整段读入再整段写出），内存占用从"分片大小的 3 倍"
     * 降到固定的一块缓冲区——50MB 的大分片也不会把内存打爆。
     */
    fun decryptFile(src: File, dst: File, key: ByteArray, iv: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        src.inputStream().buffered(BUFFER).use { raw ->
            CipherInputStream(raw, cipher).use { dec ->
                dst.outputStream().buffered(BUFFER).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = dec.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        stripFilePadding(dst)
    }

    /**
     * 就地去掉文件尾部的 PKCS#7 填充。
     *
     * 只有长度不是 188 整数倍时才需要处理——MPEG-TS 包固定 188 字节，
     * 能被整除就说明本来就没有填充。
     */
    fun stripFilePadding(file: File) {
        val size = file.length()
        if (size <= 0L || size % TS_PACKET == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(size - 1)
            val pad = raf.read()
            if (pad in 1..16 && size >= pad) {
                raf.seek(size - pad)
                var allSame = true
                for (i in 0 until pad) {
                    if (raf.read() != pad) { allSame = false; break }
                }
                if (allSame) raf.setLength(size - pad)
            }
        }
    }

    fun hexToBytes(s: String): ByteArray {
        val clean = s.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** 调试用：打印前 16 字节。 */
    fun preview(data: ByteArray): String =
        bytesToHex(data.copyOf(min(16, data.size)))
}
