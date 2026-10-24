package com.yeoncompany.photoinfoeraser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MetadataStripperTest {

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // 압축 데이터 안에 FF 00(스터핑)과 RST 마커가 섞인 가짜 JPEG
    private val scanData = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56)

    private fun buildJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(seg(0xE0, ascii("JFIF\u0000") + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)))
        out.write(seg(0xE1, ascii("Exif\u0000\u0000SECRET-GPS-37.5665")))
        out.write(seg(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000SECRET-XMP")))
        out.write(seg(0xE2, ascii("ICC_PROFILE\u0000") + byteArrayOf(1, 1, 9, 9)))
        out.write(seg(0xED, ascii("Photoshop 3.0\u0000SECRET-IPTC")))
        out.write(seg(0xFE, ascii("SECRET-COMMENT")))
        out.write(seg(0xDB, ByteArray(65) { 1 }))   // DQT
        out.write(seg(0xC0, ByteArray(15) { 2 }))   // SOF0
        out.write(seg(0xC4, ByteArray(20) { 3 }))   // DHT
        out.write(seg(0xDA, ByteArray(10) { 4 }))   // SOS 헤더
        out.write(scanData)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        out.write(ascii("SECRET-TRAILER-AFTER-EOI"))
        return out.toByteArray()
    }

    @Test
    fun jpeg_removesMetadataSegmentsAndTrailer() {
        val cleaned = MetadataStripper.stripJpeg(buildJpeg())!!
        for (secret in listOf("SECRET-GPS", "SECRET-XMP", "SECRET-IPTC", "SECRET-COMMENT", "SECRET-TRAILER")) {
            assertFalse("$secret 가 남아 있음", contains(cleaned, ascii(secret)))
        }
    }

    @Test
    fun jpeg_keepsImageAndColorSegmentsUntouched() {
        val cleaned = MetadataStripper.stripJpeg(buildJpeg())!!
        assertTrue(contains(cleaned, ascii("JFIF")))
        assertTrue(contains(cleaned, ascii("ICC_PROFILE")))
        assertTrue(contains(cleaned, seg(0xDB, ByteArray(65) { 1 })))
        assertTrue(contains(cleaned, seg(0xC0, ByteArray(15) { 2 })))
        assertTrue(contains(cleaned, seg(0xDA, ByteArray(10) { 4 }) + scanData))
        assertEquals(0xD9, cleaned.last().toInt() and 0xFF)
        assertEquals(0xD8, cleaned[1].toInt() and 0xFF)
    }

    @Test
    fun jpeg_writesOnlyOrientationWhenRotated() {
        val cleaned = MetadataStripper.stripJpeg(buildJpeg(), orientation = 6)!!
        val app1 = MetadataStripper.minimalOrientationApp1(6)
        // SOI 바로 뒤에 방향 태그만 담은 EXIF가 들어감
        assertArrayEquals(app1, cleaned.copyOfRange(2, 2 + app1.size))
        assertFalse(contains(cleaned, ascii("SECRET-GPS")))
    }

    @Test
    fun jpeg_normalOrientationAddsNoExif() {
        val cleaned = MetadataStripper.stripJpeg(buildJpeg(), orientation = 1)!!
        assertFalse(contains(cleaned, ascii("Exif")))
    }

    @Test
    fun jpeg_rejectsBrokenFile() {
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00, 1, 2)
        assertNull(MetadataStripper.stripJpeg(broken))
        assertNull(MetadataStripper.strip(ascii("not an image at all")))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) +
                ascii(type) + data + byteArrayOf(0, 0, 0, 0)
    }

    @Test
    fun png_removesTextAndExifChunks() {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val png = sig +
                chunk("IHDR", ByteArray(13) { 1 }) +
                chunk("tEXt", ascii("Author\u0000SECRET-NAME")) +
                chunk("eXIf", ascii("SECRET-EXIF")) +
                chunk("iCCP", ascii("icc\u0000\u0000x")) +
                chunk("IDAT", ByteArray(20) { 7 }) +
                chunk("tIME", ByteArray(7)) +
                chunk("IEND", ByteArray(0)) +
                ascii("SECRET-TRAILER")

        val cleaned = MetadataStripper.strip(png)
        assertNotNull(cleaned)
        cleaned!!
        assertFalse(contains(cleaned, ascii("SECRET")))
        assertFalse(contains(cleaned, ascii("tIME")))
        assertTrue(contains(cleaned, chunk("IDAT", ByteArray(20) { 7 })))
        assertTrue(contains(cleaned, ascii("iCCP")))
        assertTrue(contains(cleaned, chunk("IEND", ByteArray(0))))
    }

    // ---------- WebP ----------

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun riffChunk(type: String, data: ByteArray): ByteArray =
        ascii(type) + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ascii("RIFF") + le32(body.size) + body
    }

    // VP8X 플래그 0x0C = EXIF + XMP 있음
    private val vp8x = byteArrayOf(0x0C, 0, 0, 0, 9, 0, 0, 9, 0, 0)
    private val imageData = ByteArray(11) { 5 } // 홀수 크기 → 채움 바이트 확인

    private fun buildWebp() = riff(
        riffChunk("VP8X", vp8x),
        riffChunk("ICCP", ascii("icc")),
        riffChunk("VP8 ", imageData),
        riffChunk("EXIF", ascii("MM\u0000*SECRET-GPS")),
        riffChunk("XMP ", ascii("SECRET-XMP"))
    )

    @Test
    fun webp_removesExifAndXmpAndClearsFlags() {
        val cleaned = MetadataStripper.strip(buildWebp())!!
        assertFalse(contains(cleaned, ascii("SECRET")))
        assertTrue(contains(cleaned, riffChunk("VP8 ", imageData)))
        assertTrue(contains(cleaned, riffChunk("ICCP", ascii("icc"))))
        // VP8X 플래그에서 EXIF·XMP 비트가 꺼져야 함
        assertEquals(0, cleaned[20].toInt() and 0x0C)
        // RIFF 크기 = 전체 - 8
        val riffSize = (cleaned[4].toInt() and 0xFF) or ((cleaned[5].toInt() and 0xFF) shl 8) or
                ((cleaned[6].toInt() and 0xFF) shl 16) or ((cleaned[7].toInt() and 0xFF) shl 24)
        assertEquals(cleaned.size - 8, riffSize)
        assertEquals("image/webp", MetadataStripper.mimeOf(cleaned))
    }

    @Test
    fun webp_keepsOnlyOrientationWhenRotated() {
        val cleaned = MetadataStripper.strip(buildWebp(), orientation = 6)!!
        assertFalse(contains(cleaned, ascii("SECRET")))
        assertTrue(contains(cleaned, riffChunk("EXIF", MetadataStripper.minimalOrientationTiff(6))))
        assertEquals(0x08, cleaned[20].toInt() and 0x0C)
    }

    @Test
    fun webp_simpleFormatNeedingRotationFallsBack() {
        // VP8X가 없는 단순 WebP는 방향을 기록할 곳이 없어 재인코딩으로 넘김
        val simple = riff(riffChunk("VP8 ", imageData))
        assertNotNull(MetadataStripper.strip(simple, orientation = 1))
        assertNull(MetadataStripper.strip(simple, orientation = 6))
    }

    @Test
    fun heif_isDetectedByBrand() {
        val heic = byteArrayOf(0, 0, 0, 24) + ascii("ftypheic") + ByteArray(12)
        assertTrue(MetadataStripper.isHeif(heic))
        assertNull(MetadataStripper.strip(heic))
        assertFalse(MetadataStripper.isHeif(buildWebp()))
    }
}
