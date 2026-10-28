package ai.chagok.app.data

import ai.chagok.app.model.Driver
import kotlin.test.Test
import kotlin.test.assertEquals

class DriverCodecTest {
    @Test
    fun roundTripKeepsMissingSeat() {
        val list = listOf(Driver("kim", "김도윤", "영업팀", 42), Driver("lee", "이미등", "총무팀", null))
        assertEquals(list, decodeDrivers(encodeDrivers(list)))
    }

    @Test
    fun tabsAndNewlinesInNamesDoNotBreakRows() {
        val d = Driver("x", "홍\t길동", "영업\n1팀", 10)
        assertEquals(listOf(Driver("x", "홍 길동", "영업 1팀", 10)), decodeDrivers(encodeDrivers(listOf(d))))
    }

    @Test
    fun emptyOrBrokenRowsAreSkipped() {
        assertEquals(emptyList(), decodeDrivers(""))
        assertEquals(listOf(Driver("a", "가", "팀", null)), decodeDrivers("a\t가\t팀\t\nbroken\n"))
    }
}
