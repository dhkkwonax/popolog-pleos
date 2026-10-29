package ai.chagok.app.core

import ai.chagok.app.model.Driver
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.TripPurpose

/**
 * 앱이 먼저 묻고 받는 짧은 대답(네/아니오, 종류, 목적, 사람)을 가린다. 자유 문장 해석기가 아니라 질문마다 정해진 보기와 맞춰 본다.
 * 금액은 [KoreanAmountParser] 가 읽는다.
 */
object AnswerParser {
    private val NO = listOf("아니", "아냐", "안 ", "싫", "취소", "말고", "나중", "저장 안", "하지 마", "하지마")
    private val YES = listOf("네", "예", "응", "맞", "그래", "저장", "좋아", "좋습니다", "오케이", "ok", "확인", "했어")

    /** 긍정이면 true, 부정이면 false, 알 수 없으면 null. 부정을 먼저 본다("저장 안 해"가 "저장"으로 읽히지 않게) */
    fun yesNo(said: String): Boolean? {
        val s = said.trim().lowercase()
        if (s.isEmpty()) return null
        if (NO.any { it in s }) return false
        if (YES.any { it in s }) return true
        return null
    }

    /** 장소 저장 질문의 대답: 저장 / 저장 안 함 / 다시 묻지 않기 */
    fun saveChoice(said: String): String? {
        val s = said.trim()
        if (listOf("다시 묻지", "묻지 마", "묻지마", "그만").any { it in s }) return "never"
        return when (yesNo(s)) {
            true -> "yes"
            false -> "no"
            null -> null
        }
    }

    fun placeKind(said: String): PlaceKind? {
        val s = said.trim()
        return when {
            listOf("거래처", "고객", "업체", "납품", "현장").any { it in s } -> PlaceKind.CLIENT
            "집" in s || "자택" in s -> PlaceKind.HOME
            listOf("회사", "사무실", "본사", "사업장").any { it in s } -> PlaceKind.OFFICE
            listOf("기타", "그 외", "그외", "다른", "개인").any { it in s } -> PlaceKind.OTHER
            else -> null
        }
    }

    fun purpose(said: String): TripPurpose? = when {
        listOf("출근", "퇴근", "출퇴근").any { it in said } -> TripPurpose.COMMUTE
        listOf("개인", "비업무", "아니", "사적").any { it in said } -> TripPurpose.PERSONAL
        listOf("업무", "미팅", "거래처", "출장", "납품", "응", "네", "맞아").any { it in said } -> TripPurpose.BUSINESS
        else -> null
    }

    /** 말한 문장에 이름이 들어 있는 사람. 이름이 겹쳐 둘 이상이면 판단하지 않는다 */
    fun driver(said: String, drivers: List<Driver>): Driver? {
        val s = said.replace(" ", "")
        val hit = drivers.filter { it.name.isNotBlank() && s.contains(it.name.replace(" ", "")) }
        return hit.singleOrNull()
    }
}
