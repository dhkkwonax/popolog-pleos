package ai.chagok.app.core

/**
 * STT 결과 문장에서 금액을 뽑는다.
 * "오만 삼천원", "5만3천 원", "53,000원", "주유 칠만 원 했어" → 원 단위 Long
 */
object KoreanAmountParser {
    private val digits = mapOf(
        '영' to 0L, '공' to 0L, '일' to 1L, '이' to 2L, '삼' to 3L, '사' to 4L, '오' to 5L,
        '육' to 6L, '륙' to 6L, '칠' to 7L, '팔' to 8L, '구' to 9L,
    )
    private val smallUnits = mapOf('십' to 10L, '백' to 100L, '천' to 1_000L)
    private val bigUnits = mapOf('만' to 10_000L, '억' to 100_000_000L)

    fun parse(text: String): Long? {
        val s = text.replace(",", "").replace(" ", "")
        var total = 0L
        var section = 0L
        var num = -1L
        var seen = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    num = s.substring(i, j).toLong()
                    seen = true
                    i = j
                    continue
                }
                c in digits && isNumberContext(s, i) -> {
                    num = digits.getValue(c)
                    seen = true
                }
                c in smallUnits && (seen || isNumberContext(s, i)) -> {
                    section += (if (num >= 0) num else 1L) * smallUnits.getValue(c)
                    num = -1
                    seen = true
                }
                c in bigUnits && (seen || isNumberContext(s, i)) -> {
                    section += if (num >= 0) num else 0L
                    if (section == 0L) section = 1L
                    total += section * bigUnits.getValue(c)
                    section = 0
                    num = -1
                    seen = true
                }
                seen && c == '원' -> break
                seen -> break
            }
            i++
        }
        if (!seen) return null
        total += section + (if (num >= 0) num else 0L)
        return total.takeIf { it > 0 }
    }

    /** "이번에", "오늘" 같은 일반 단어 속 글자를 숫자로 읽지 않도록 앞뒤가 숫자 단위인지 본다 */
    private fun isNumberContext(s: String, i: Int): Boolean {
        val numeric = digits.keys + smallUnits.keys + bigUnits.keys + '원'
        val next = s.getOrNull(i + 1)
        val prev = s.getOrNull(i - 1)
        return (next != null && (next in numeric || next.isDigit())) ||
            (prev != null && (prev in numeric || prev.isDigit()))
    }
}
