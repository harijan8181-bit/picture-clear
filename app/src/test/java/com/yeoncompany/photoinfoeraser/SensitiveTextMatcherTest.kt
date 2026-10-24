package com.yeoncompany.photoinfoeraser

import com.yeoncompany.photoinfoeraser.SensitiveTextMatcher.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTextMatcherTest {

    private fun match(line: String) = SensitiveTextMatcher.findMatches(line.split(" "))

    private fun categories(line: String) = match(line).map { it.category }

    @Test
    fun residentRegistrationNumber() {
        assertEquals(listOf(Category.RRN), categories("주민번호확인 900101-1234567"))
        assertEquals(listOf(Category.RRN), categories("9001011234567"))
        // 뒷자리가 일부 가려진 경우도 감지
        assertEquals(listOf(Category.RRN), categories("850315-2******"))
    }

    @Test
    fun phoneNumbers() {
        assertEquals(listOf(Category.PHONE), categories("010-1234-5678"))
        assertEquals(listOf(Category.PHONE), categories("휴대 01012345678"))
        assertEquals(listOf(Category.PHONE), categories("0507-1234-5678"))
        assertEquals(listOf(Category.PHONE), categories("02-123-4567"))
        // OCR이 숫자를 띄어서 읽은 경우: 세 단어 모두 가림
        val m = match("010 1234 5678").single()
        assertEquals(Category.PHONE, m.category)
        assertEquals(0..2, m.tokenIndices)
    }

    @Test
    fun accountAndCardNumbers() {
        assertEquals(listOf(Category.ACCOUNT), categories("국민 123456-01-234567"))
        assertEquals(listOf(Category.ACCOUNT), categories("1234-5678-9012-3456"))
    }

    @Test
    fun trackingNumber() {
        assertEquals(listOf(Category.LONG_NUMBER), categories("운송장 6012345678901"))
    }

    @Test
    fun emailOnlyMasksTheEmailToken() {
        val m = match("문의 hong.gildong@example.com 으로").single()
        assertEquals(Category.EMAIL, m.category)
        assertEquals(1..1, m.tokenIndices)
    }

    @Test
    fun addressLinesAreMaskedWhole() {
        for (line in listOf("서울 강남구 테헤란로 123", "역삼동 123-4", "래미안 101동 1203호", "경기도 성남시 분당구")) {
            val m = match(line).single()
            assertEquals(line, Category.ADDRESS, m.category)
            assertEquals(0 until line.split(" ").size, m.tokenIndices)
        }
    }

    @Test
    fun labeledLinesAreMaskedWhole() {
        assertEquals(listOf(Category.LABELED_LINE), categories("받는분 홍길동"))
        assertEquals(listOf(Category.LABELED_LINE), categories("수령인: 김철수 님"))
    }

    @Test
    fun ordinaryTextIsIgnored() {
        assertTrue(categories("오늘 날씨가 좋네요").isEmpty())
        assertTrue(categories("가격 25,000원 2024.10.16").isEmpty())
        assertTrue(categories("3호선 환승").isEmpty())
    }

    // ---------- 미국 형식 ----------

    @Test
    fun usSocialSecurityNumber() {
        assertEquals(listOf(Category.SSN), categories("SSN# 123-45-6789"))
        assertEquals(listOf(Category.SSN), categories("123 45 6789"))
        // 발급되지 않는 번호대(000, 666, 9xx)는 제외
        assertTrue(categories("000-12-3456").isEmpty())
    }

    @Test
    fun usPhoneNumbers() {
        assertEquals(listOf(Category.PHONE), categories("Call (212) 555-1234 today"))
        assertEquals(listOf(Category.PHONE), categories("212-555-1234"))
        assertEquals(listOf(Category.PHONE), categories("+1 415.555.0198"))
        // 한국 번호는 그대로 한국 형식으로 감지
        assertEquals(listOf(Category.PHONE), categories("010-1234-5678"))
    }

    @Test
    fun usTrackingNumbers() {
        assertEquals(listOf(Category.LONG_NUMBER), categories("1Z999AA10123456784"))
        assertEquals(listOf(Category.LONG_NUMBER), categories("9400 1000 0000 0000 0000 00"))
        assertEquals(listOf(Category.LONG_NUMBER), categories("9261290100130431234567"))
    }

    @Test
    fun usAddressesAndLabels() {
        assertEquals(listOf(Category.ADDRESS), categories("1600 Pennsylvania Ave NW"))
        assertEquals(listOf(Category.ADDRESS), categories("4500 N Lake Shore Dr."))
        assertEquals(listOf(Category.ADDRESS), categories("Apt 4B"))
        assertEquals(listOf(Category.ADDRESS), categories("Springfield, IL 62704"))
        assertEquals(listOf(Category.ADDRESS), categories("PO Box 123"))
        assertEquals(listOf(Category.LABELED_LINE), categories("SHIP TO John Smith"))
        assertEquals(listOf(Category.LABELED_LINE), categories("Name: Jane Doe"))
    }

    @Test
    fun usOrdinaryTextIsIgnored() {
        assertTrue(categories("Meet me at the station at 5").isEmpty())
        assertTrue(categories("Total 25.00 on 10/16/2026").isEmpty())
        assertTrue(categories("Our name is on the door").isEmpty())
        assertTrue(categories("Open 24 hours, 7 days").isEmpty())
    }
}
