package ai.chagok.app.core

/**
 * 사업자 유형. 운행기록부 별지 서식은 소득세법 고시(국세청 고시 제2025-5호)와 법인세법 고시(제2025-8호)가
 * 항목·표 구조·작성 방법이 같고, 머리글과 합계 칸의 용어만 다르다.
 */
enum class Taxpayer(
    val key: String,
    val label: String,
    val law: String,
    /** 서식 왼쪽 위 칸과 ⑪⑫ 합계 칸에 쓰는 기간 용어 */
    val period: String,
    /** 서식 오른쪽 위 첫 칸. 서식 표기대로 글자 사이를 띄운다 */
    val nameCell: String,
) {
    PERSONAL("personal", "개인사업자", "소득세법", "과세기간", "상 호 명"),
    CORPORATE("corp", "법인", "법인세법", "사업연도", "법인명");

    /** 입력칸·CSV 처럼 띄어쓰기 없이 쓰는 이름 */
    val nameLabel: String get() = nameCell.replace(" ", "")

    companion object {
        fun of(key: String): Taxpayer = values().firstOrNull { it.key == key } ?: PERSONAL
    }
}
