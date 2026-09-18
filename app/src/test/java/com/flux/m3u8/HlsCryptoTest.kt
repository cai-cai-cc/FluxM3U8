package com.flux.m3u8

import com.flux.m3u8.crypto.HlsCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsCryptoTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val key = HlsCrypto.hexToBytes("00112233445566778899aabbccddeeff")
    private val iv = HlsCrypto.hexToBytes("000102030405060708090a0b0c0d0e0f")

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plain)
    }

    @Test
    fun binaryKeyIsReturnedAsIs() {
        assertArrayEquals(key, HlsCrypto.normalizeKey(key))
        assertEquals(16, HlsCrypto.normalizeKey(key).size)
    }

    @Test
    fun hexTextKeyIsConverted() {
        val text = "00112233445566778899aabbccddeeff".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(key, HlsCrypto.normalizeKey(text))
    }

    @Test
    fun hexTextKeyOf64CharsBecomes32Bytes() {
        val text = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
            .toByteArray(Charsets.US_ASCII)
        assertEquals(32, HlsCrypto.normalizeKey(text).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyKeyThrows() {
        HlsCrypto.normalizeKey(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooShortNonHexKeyThrows() {
        HlsCrypto.normalizeKey(ByteArray(5))
    }

    @Test
    fun ivFromHexWith0xPrefix() {
        assertArrayEquals(iv, HlsCrypto.ivFromHex("0x000102030405060708090a0b0c0d0e0f"))
    }

    @Test
    fun shortIvIsLeftPadded() {
        val r = HlsCrypto.ivFromHex("0xff")
        assertEquals(16, r.size)
        assertEquals(0, r[14].toInt())
        assertEquals(0xFF, r[15].toInt() and 0xFF)
    }

    @Test
    fun ivFromSequenceIsBigEndian() {
        val r = HlsCrypto.ivFromSequence(1)
        assertEquals(16, r.size)
        assertEquals(1, r[15].toInt())
        assertEquals(0, r[0].toInt())

        val big = HlsCrypto.ivFromSequence(256)
        assertEquals(1, big[14].toInt())
        assertEquals(0, big[15].toInt())
    }

    @Test
    fun decryptStripsPkcs7Padding() {
        val plain = ByteArray(100) { it.toByte() }
        val padded = plain + ByteArray(12) { 12.toByte() }
        assertEquals(0, padded.size % 16)

        assertArrayEquals(plain, HlsCrypto.decrypt(encrypt(padded), key, iv))
    }

    @Test
    fun decryptKeepsDataWhenMultipleOf188() {
        // MPEG-TS 包 188 字节：能被整除说明本来就没填充，不能误删。
        // 188 = 4 × 47，所以「既是 188 的倍数、又是 16 的倍数」的最小长度是 4 个包
        // （752 字节）——只有这样打包端才不需要补位，AES 无填充加密也才接受这段明文。
        val packets = 4
        val plain = ByteArray(188 * packets) { 0x47.toByte() }
        assertEquals(0, plain.size % 16)      // 无需补位，加密端不会加 pad
        assertEquals(188 * packets, HlsCrypto.decrypt(encrypt(plain), key, iv).size)
    }

    @Test
    fun decryptFileProducesSameContent() {
        val plain = ByteArray(100) { it.toByte() }
        val padded = plain + ByteArray(12) { 12.toByte() }

        val src = File(tmp.newFolder("c"), "seg.part")
        val dst = File(src.parentFile, "seg.dec")
        src.writeBytes(encrypt(padded))

        HlsCrypto.decryptFile(src, dst, key, iv)
        assertArrayEquals(plain, dst.readBytes())
        assertEquals(100, dst.length())
    }

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte())
        assertEquals("000f10ff", HlsCrypto.preview(bytes))
        assertArrayEquals(bytes, HlsCrypto.hexToBytes("000f10ff"))
    }

    @Test
    fun stripFilePaddingOnlyForNonPacketAlignedFiles() {
        val dir = tmp.newFolder("p")

        val a = File(dir, "a.ts")
        a.writeBytes(ByteArray(100) { 0x47.toByte() } + ByteArray(4) { 4.toByte() })
        HlsCrypto.stripFilePadding(a)
        assertEquals(100, a.length())

        val b = File(dir, "b.ts")
        b.writeBytes(ByteArray(188) { 0x47.toByte() })
        HlsCrypto.stripFilePadding(b)
        assertEquals(188, b.length())
        assertTrue(b.length() > 0)
    }
}
