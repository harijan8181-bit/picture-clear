package com.harijan.exiferaser

import com.harijan.exiferaser.SensitiveTextMatcher.Category
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
}
