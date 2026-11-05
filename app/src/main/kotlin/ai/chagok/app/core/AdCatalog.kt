package ai.chagok.app.core

/**
 * 데모 광고 소재. 실제 광고 서버·광고주 연동은 이 변경의 범위 밖이다.
 * 글자는 최소로, 어두운 톤의 정지 이미지로 그린다 (번쩍임·대비 급변 없음).
 */
data class AdCreative(
    val sponsor: String,
    val headline: String,
    val sub: String,
    val bgNight: Int,
    val bgDay: Int,
    val voiceText: String,
)

object AdCatalog {
    val all = listOf(
        AdCreative("○○카페", "충전하는 동안 아메리카노 1,000원 할인", "근처 ○○카페 · 300m", 0xFF1B2A26.toInt(), 0xFFD9E6E1.toInt(),
            "광고입니다. 근처 ○○카페에서 충전 시간 아메리카노 할인을 해요. 안내할까요?"),
        AdCreative("△△세차", "오늘 세차 2,000원 할인", "근처 △△세차 · 1.2km", 0xFF1E2230.toInt(), 0xFFDDE2EE.toInt(),
            "광고입니다. 근처 △△세차에서 오늘 할인을 해요. 안내할까요?"),
        AdCreative("□□휴게소", "따뜻한 우동 세트 할인", "다음 휴게소 □□ · 8km", 0xFF2B2420.toInt(), 0xFFEBE0D8.toInt(),
            "광고입니다. 다음 □□휴게소에서 우동 세트 할인을 해요. 안내할까요?"),
    )
}
