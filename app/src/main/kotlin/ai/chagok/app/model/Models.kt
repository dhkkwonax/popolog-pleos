package ai.chagok.app.model

/** 지오펜스로 쓰는 장소. 거래처 주소와 도착 위치를 비교해 업무 운행을 분류한다. */
data class Place(
    val id: Long = 0,
    val name: String,
    val kind: PlaceKind,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 300,
    val address: String = "",
)

enum class PlaceKind(val label: String) {
    HOME("집"),
    OFFICE("회사"),
    CLIENT("거래처"),
}

enum class TripPurpose(val label: String) {
    COMMUTE("출퇴근"),
    BUSINESS("일반업무"),
    PERSONAL("비업무"),
    UNCLASSIFIED("미분류"),
    ;

    /** 국세청 운행기록부 기준: 출퇴근도 업무용 사용거리에 포함된다. */
    val isBusinessUse: Boolean get() = this == COMMUTE || this == BUSINESS
}

enum class ClassifiedBy(val label: String) {
    PLACE("도착지 자동"),
    VOICE("음성"),
    MANUAL("직접"),
    NONE("-"),
}

data class Trip(
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val startOdoKm: Double,
    val endOdoKm: Double,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val purpose: TripPurpose = TripPurpose.UNCLASSIFIED,
    val classifiedBy: ClassifiedBy = ClassifiedBy.NONE,
    val fromName: String = "",
    val toName: String = "",
    val memo: String = "",
    val driver: String = "",
) {
    val distanceKm: Double get() = (endOdoKm - startOdoKm).coerceAtLeast(0.0)
}

enum class ExpenseCategory(val label: String, val emoji: String) {
    FUEL("주유", "⛽"),
    CHARGE("충전", "🔌"),
    TOLL("통행료", "🛣"),
    PARKING("주차", "🅿"),
    MAINTENANCE("정비", "🔧"),
    INSURANCE("보험", "🛡"),
    TAX("자동차세", "🧾"),
    LEASE("렌트·리스", "📄"),
    WASH("세차", "🫧"),
    OTHER("기타", "•"),
}

enum class ExpenseSource(val label: String) {
    AUTO("자동 감지"),
    VOICE("음성 입력"),
    MANUAL("직접 입력"),
}

data class Expense(
    val id: Long = 0,
    val time: Long,
    val category: ExpenseCategory,
    val amountWon: Long,
    val odoKm: Double? = null,
    /** 주유 L 또는 충전 kWh (자동 감지 시 계기판 값 변화로 추정) */
    val quantity: Double? = null,
    val memo: String = "",
    val source: ExpenseSource = ExpenseSource.MANUAL,
    /** 자동 감지됐지만 금액이 아직 입력되지 않은 항목 */
    val pendingAmount: Boolean = false,
)

enum class Powertrain { ICE, EV }

data class VehicleInfo(
    val modelName: String,
    val plateNumber: String,
    val vin: String,
    val powertrain: Powertrain,
    /** 연료 탱크 용량(L) 또는 배터리 용량(kWh) */
    val energyCapacity: Double,
) {
    /** "그랜저 2.5 가솔린 · 123가 4567". 번호판을 아직 못 읽었으면 차종만 */
    val title: String
        get() = listOf(modelName, plateNumber).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 이 차를 모는 사람. 운행기록부의 부서·성명 칸에 들어간다 */
data class Driver(
    val id: String,
    val name: String,
    val department: String,
    /** 이 사람이 맞춰 둔 운전석 앞뒤 위치 (Vehicle SDK Seat 단위). 등록 전이면 null */
    val seatPosition: Int? = null,
)

/** 시동 때 운전자를 알아낸 방법 */
enum class DriverMethod(val label: String) {
    SEAT("운전석 시트 위치"),
    ROUTINE("출발 시간·장소"),
    LAST("마지막 운전자"),
    MANUAL("직접 선택"),
}
