package com.harijan.exiferaser

/**
 * OCR로 읽은 한 줄의 단어 목록에서 개인정보를 찾아, 가려야 할 단어 위치를 돌려주는 순수 로직
 * (안드로이드 의존성이 없어 단위 테스트 가능)
 *
 * OCR은 글자를 놓치거나 잘못 읽을 수 있으므로 결과는 반드시 사용자 확인을 거쳐 적용해야 함
 */
object SensitiveTextMatcher {

    enum class Category {
        RRN, SSN, PHONE, EMAIL, ACCOUNT, LONG_NUMBER, ADDRESS, LABELED_LINE, BARCODE
    }

    /** category 종류의 정보가 tokenIndices 위치의 단어들에 들어 있음 */
    data class Match(val category: Category, val tokenIndices: IntRange)

    private val D = "[0-9]"

    // 숫자 패턴: 앞뒤가 숫자가 아니어야 함 (더 긴 숫자의 일부를 잘못 잡지 않도록)
    private val numberPatterns: List<Pair<Category, Regex>> = listOf(
        // 생년월일 6자리 + 성별 1자리 + 6자리 (뒷자리 일부가 * 로 가려진 경우 포함)
        Category.RRN to Regex("(?<![0-9])$D{2}(0[1-9]|1[0-2])(0[1-9]|[12]$D|3[01])\\s?[-–~]?\\s?[1-8][0-9*xX]{6}(?![0-9])"),
        // 미국 사회보장번호 123-45-6789 (구분자가 있는 형태만: 9자리 숫자만으로는 다른 번호와 구별이 어려움)
        Category.SSN to Regex("(?<![0-9])(?!000|666|9)$D{3}[- ]$D{2}[- ]$D{4}(?![0-9])"),
        // 휴대폰·안심번호(050x)·인터넷전화·지역번호
        Category.PHONE to Regex("(?<![0-9])(01[016789]|050$D|070|080|02|0[3-6][1-5])\\s?[-.)]?\\s?[0-9*]{3,4}\\s?[-.]?\\s?[0-9*]{4}(?![0-9])"),
        // 미국·캐나다 전화번호: (212) 555-1234, 212-555-1234, +1 212.555.1234
        Category.PHONE to Regex("(?<![0-9])(\\+?1[-. ]?)?(\\([2-9]$D{2}\\)\\s?|[2-9]$D{2}[-. ])$D{3}[-. ]$D{4}(?![0-9])"),
        Category.EMAIL to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        // USPS 추적번호: 4자리씩 띄어 쓴 20~22자리 (카드번호보다 먼저 확인)
        Category.LONG_NUMBER to Regex("(?<![0-9])$D{4}( $D{4}){4}( $D{2,4})?(?![0-9])"),
        // 카드번호 4-4-4-4
        Category.ACCOUNT to Regex("(?<![0-9])$D{4}[- ]$D{4}[- ]$D{4}[- ]$D{3,4}(?![0-9])"),
        // 하이픈으로 나뉜 계좌번호 (숫자 합계 10~16자리는 아래에서 확인)
        Category.ACCOUNT to Regex("(?<![0-9])$D{2,6}-$D{2,6}-$D{2,7}(-$D{1,3})?(?![0-9])"),
        // 송장번호 등 10~14자리 숫자 (하이픈 포함 가능)
        Category.LONG_NUMBER to Regex("(?<![0-9])$D{10,14}(?![0-9])"),
        Category.LONG_NUMBER to Regex("(?<![0-9])$D{4}-$D{4}-$D{4,6}(?![0-9])"),
        // UPS(1Z…), USPS·FedEx 등 15~22자리 추적번호
        Category.LONG_NUMBER to Regex("(?<![0-9A-Za-z])1Z[0-9A-Z]{16}(?![0-9A-Za-z])"),
        Category.LONG_NUMBER to Regex("(?<![0-9])$D{15,22}(?![0-9])")
    )

    // 줄 전체를 가리는 패턴
    private val addressPatterns = listOf(
        Regex("[가-힣0-9]+(로|길)\\s?$D+"),                     // 도로명 주소: 테헤란로 123, 성수이로7길 4
        Regex("[가-힣]+(동|읍|면|리)\\s?$D+(-$D+)?(번지)?"),     // 지번 주소: 역삼동 123-4
        Regex("$D+동\\s?$D+호"),                                // 101동 1203호
        Regex("$D+호(?![가-힣])"),
        Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|충청|전북|전남|전라|경북|경남|경상|제주)[가-힣]*\\s+[가-힣]+(시|군|구)"),
        // 미국 주소: 123 Main St, 4500 N Lake Shore Dr.
        Regex(
            "\\b$D{1,6}\\s+([A-Za-z0-9.'-]+\\s+){0,4}(St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Ln|Lane|" +
                    "Ct|Court|Way|Pl|Place|Ter|Terrace|Pkwy|Parkway|Hwy|Highway|Cir|Circle|Sq|Square|Trl|Trail)\\b\\.?",
            RegexOption.IGNORE_CASE
        ),
        Regex("\\b(Apt|Apartment|Suite|Ste|Unit)\\.?\\s*#?\\s*[0-9][0-9A-Za-z-]*\\b", RegexOption.IGNORE_CASE),
        Regex("\\bP\\.?\\s?O\\.?\\s+Box\\s+$D+", RegexOption.IGNORE_CASE),
        // 도시, 주 약자 우편번호: Springfield, IL 62704
        Regex("[A-Za-z .'-]+,\\s*[A-Z]{2}\\s+$D{5}(-$D{4})?(?![0-9])")
    )

    private val labelPattern = Regex(
        "(받는\\s?분|보내는\\s?분|받는\\s?사람|보내는\\s?사람|수령인|수취인|수하인|송하인|성\\s?명|고객명|예금주|연락처|휴대폰|핸드폰|전화번호|주\\s?소|생년월일|주민등록)"
    )

    // 영어 송장·서류의 이름/연락처 표기 (일반 문장과 헷갈리지 않도록 대부분 콜론이 있어야 인정)
    private val englishLabelPattern = Regex(
        "\\b(ship(ping)?\\s?to|bill(ing)?\\s?to|deliver\\s?to|recipient|sender|consignee|shipper)\\b|" +
                "\\b(name|full\\s?name|to|from|phone|tel|mobile|cell|address|addr|dob|date\\s?of\\s?birth|ssn|social\\s?security)\\s?:",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param tokens OCR 한 줄의 단어들 (순서대로)
     */
    fun findMatches(tokens: List<String>): List<Match> {
        if (tokens.isEmpty()) return emptyList()

        // 단어를 공백으로 이어 붙이고 각 단어의 문자 범위를 기억
        val starts = IntArray(tokens.size)
        val sb = StringBuilder()
        tokens.forEachIndexed { i, t ->
            if (i > 0) sb.append(' ')
            starts[i] = sb.length
            sb.append(t)
        }
        val line = sb.toString()

        val wholeLine = 0 until tokens.size
        if (labelPattern.containsMatchIn(line) || englishLabelPattern.containsMatchIn(line)) {
            return listOf(Match(Category.LABELED_LINE, wholeLine))
        }
        if (addressPatterns.any { it.containsMatchIn(line) }) {
            return listOf(Match(Category.ADDRESS, wholeLine))
        }

        val claimed = BooleanArray(line.length)
        val result = mutableListOf<Match>()
        for ((category, regex) in numberPatterns) {
            for (m in regex.findAll(line)) {
                val range = m.range
                if (range.any { claimed[it] }) continue
                if (category == Category.ACCOUNT && m.value.count { it.isDigit() } !in 10..16) continue
                range.forEach { claimed[it] = true }

                val first = tokenAt(starts, range.first)
                val last = tokenAt(starts, range.last)
                result.add(Match(category, first..last))
            }
        }
        return result
    }

    private fun tokenAt(starts: IntArray, charIndex: Int): Int {
        var idx = 0
        for (i in starts.indices) {
            if (starts[i] <= charIndex) idx = i else break
        }
        return idx
    }
}
