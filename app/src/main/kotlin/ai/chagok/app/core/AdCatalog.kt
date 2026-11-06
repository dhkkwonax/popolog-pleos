package ai.chagok.app.core

/**
 * 데모 광고 소재(현대차·기아 매장이 근처에 있다고 가정). 실제 광고 서버·광고주 연동과 브랜드 로고·사진 사용은 이 변경의 범위 밖이라
 * 로고 없이 글자로만 그린다.
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
        AdCreative("현대자동차 ○○지점", "점검·소모품 교환, 가까운 현대차 매장에서", "근처 현대자동차 ○○지점 · 1.5km", 0xFF1A2433.toInt(), 0xFFDCE4F0.toInt(),
            "Popolog가 전하는 광고입니다. 근처 현대자동차 매장에서 점검과 소모품 교환을 받을 수 있어요. 안내할까요?"),
        AdCreative("기아 ○○지점", "시승 신청하고 가까운 기아 매장 방문", "근처 기아 ○○지점 · 2.3km", 0xFF2A1F22.toInt(), 0xFFEFDFE2.toInt(),
            "Popolog가 전하는 광고입니다. 근처 기아 매장에서 시승을 신청할 수 있어요. 안내할까요?"),
        AdCreative("현대자동차 ○○서비스센터", "정기 점검 예약 시 엔진오일 교환 할인", "근처 현대자동차 ○○서비스센터 · 3.1km", 0xFF1B2A26.toInt(), 0xFFD9E6E1.toInt(),
            "Popolog가 전하는 광고입니다. 근처 현대자동차 서비스센터에서 정기 점검 예약 할인을 해요. 안내할까요?"),
    )
}
