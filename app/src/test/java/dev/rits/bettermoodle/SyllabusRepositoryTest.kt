package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.SyllabusRecord
import dev.rits.bettermoodle.data.SyllabusRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SyllabusRepositoryTest {

    @Test
    fun `検索結果のコース名から授業コードを抽出できる`() {
        assertEquals(
            "10001",
            SyllabusRecord(id = "a0i", courseName = "10001:（火）教育原理(GA)").courseCode,
        )
        assertNull(SyllabusRecord(id = "a0i", courseName = "コードなし").courseCode)
    }

    @Test
    fun `学年暦は4月始まり`() {
        assertEquals(2026, SyllabusRepository.academicYear(LocalDate.of(2026, 7, 7)))
        assertEquals(2026, SyllabusRepository.academicYear(LocalDate.of(2026, 4, 1)))
        assertEquals(2025, SyllabusRepository.academicYear(LocalDate.of(2026, 3, 31)))
    }

    @Test
    fun `Aura応答のエラーガードを除去できる`() {
        assertEquals("{\"a\":1}", SyllabusRepository.cleanAuraJson("{\"a\":1}/*ERROR*/"))
        assertEquals("{\"a\":1}", SyllabusRepository.cleanAuraJson("{\"a\":1}"))
    }
}
