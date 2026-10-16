package com.example.myapplication009

import java.io.ByteArrayOutputStream

/**
 * 재압축 없이(무손실) 사진 파일에서 메타데이터만 잘라내는 도구
 * - JPEG: EXIF/XMP(APP1), IPTC(APP13), 주석(COM), 제조사 확장(APPn), EOI 뒤 꼬리 데이터 제거
 *         색상 표현에 필요한 JFIF(APP0), ICC 프로필(APP2), Adobe(APP14)는 유지
 *         회전 정보는 다른 정보 없이 방향 태그 하나만 담은 최소 EXIF로 다시 기록
 * - PNG : 이미지·색상 표현에 필요한 청크만 남기고 텍스트/EXIF/시간 청크 제거
 * 화소 데이터는 한 바이트도 바뀌지 않으므로 화질과 해상도가 그대로 유지됨
 */
object MetadataStripper {

    /** 지원하지 않는 형식이거나 파일 구조가 깨져 있으면 null 반환 (호출 측에서 재인코딩으로 대체) */
    fun strip(bytes: ByteArray, orientation: Int = 1): ByteArray? = when {
        isJpeg(bytes) -> stripJpeg(bytes, orientation)
        isPng(bytes) -> stripPng(bytes)
        else -> null
    }

    fun isJpeg(b: ByteArray) = b.size > 4 && u(b[0]) == 0xFF && u(b[1]) == 0xD8

    fun isPng(b: ByteArray) = b.size > 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    fun stripJpeg(b: ByteArray, orientation: Int = 1): ByteArray? {
        if (!isJpeg(b)) return null
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        if (orientation in 2..8) out.write(minimalOrientationApp1(orientation))

        val n = b.size
        var i = 2
        while (i < n) {
            if (u(b[i]) != 0xFF) return null
            if (i + 1 >= n) return null
            val marker = u(b[i + 1])
            when {
                marker == 0xFF -> { i++; continue } // 채움 바이트
                marker == 0xD9 -> { out.write(0xFF); out.write(0xD9); return out.toByteArray() }
                marker == 0x01 || marker in 0xD0..0xD7 -> { out.write(b, i, 2); i += 2; continue }
            }
            if (i + 3 >= n) return null
            val len = (u(b[i + 2]) shl 8) or u(b[i + 3])
            if (len < 2) return null
            val segEnd = i + 2 + len
            if (segEnd > n) return null

            if (marker == 0xDA) {
                // SOS: 헤더 뒤의 압축 데이터는 다음 마커(RST·채움 제외)까지 그대로 복사
                var j = segEnd
                while (j < n) {
                    if (u(b[j]) == 0xFF && j + 1 < n) {
                        val m = u(b[j + 1])
                        if (m == 0x00 || m in 0xD0..0xD7) { j += 2; continue }
                        break
                    }
                    j++
                }
                out.write(b, i, j - i)
                i = j
                continue
            }

            if (keepJpegSegment(b, marker, i + 4, segEnd)) out.write(b, i, segEnd - i)
            i = segEnd
        }
        // EOI 없이 끝난 파일: 종료 마커를 붙여 마무리
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun keepJpegSegment(b: ByteArray, marker: Int, dataStart: Int, dataEnd: Int): Boolean {
        return when (marker) {
            0xE0 -> startsWith(b, dataStart, dataEnd, "JFIF\u0000")
            0xE2 -> startsWith(b, dataStart, dataEnd, "ICC_PROFILE\u0000")
            0xEE -> startsWith(b, dataStart, dataEnd, "Adobe")
            in 0xE1..0xEF -> false // EXIF, XMP, IPTC, 제조사 정보 등
            0xFE -> false // 주석(COM)
            else -> true // DQT, DHT, SOF, DRI 등 이미지 복원에 필요한 세그먼트
        }
    }

    /** 방향(0x0112) 태그 하나만 담은 최소 EXIF APP1 세그먼트 */
    fun minimalOrientationApp1(orientation: Int): ByteArray {
        val payload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,             // "Exif\0\0"
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // 빅엔디언 TIFF 헤더, IFD0 오프셋 8
            0x00, 0x01,                                     // 항목 1개
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // Orientation, SHORT, 1개
            0x00, orientation.toByte(), 0x00, 0x00,         // 값
            0x00, 0x00, 0x00, 0x00                          // 다음 IFD 없음
        )
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    fun stripPng(b: ByteArray): ByteArray? {
        if (!isPng(b)) return null
        val out = ByteArrayOutputStream(b.size)
        out.write(PNG_SIGNATURE)
        var i = 8
        while (i + 8 <= b.size) {
            val len = ((u(b[i]) shl 24) or (u(b[i + 1]) shl 16) or (u(b[i + 2]) shl 8) or u(b[i + 3]))
            if (len < 0) return null
            val chunkEnd = i + 12L + len
            if (chunkEnd > b.size) return null
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            if (type in PNG_KEEP_CHUNKS) out.write(b, i, chunkEnd.toInt() - i)
            i = chunkEnd.toInt()
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // 화면 표시에 필요한 청크만 유지 (tEXt, zTXt, iTXt, eXIf, tIME 등은 제거)
    private val PNG_KEEP_CHUNKS = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT",
        "acTL", "fcTL", "fdAT"
    )

    private fun startsWith(b: ByteArray, start: Int, end: Int, prefix: String): Boolean {
        val p = prefix.toByteArray(Charsets.US_ASCII)
        if (end - start < p.size) return false
        for (k in p.indices) if (b[start + k] != p[k]) return false
        return true
    }

    private fun u(byte: Byte) = byte.toInt() and 0xFF
}
