package com.learngraphicsprogramming.app.math

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MathInlineFractionsTest {
    @Test fun completePublishedFieldsKeepTheirFractionsTogether() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        fun lesson(path: String) = JSONObject(File(assets,
            "content/es/math_physics/math_algebra_elemental/$path").readText())
        fun block(lesson: JSONObject, page: Int, position: Int) = lesson.getJSONArray("pages")
            .getJSONObject(page - 1).getJSONArray("blocks").getJSONObject(position - 1)
        val recovery = lesson("recuperacion_y_transferencia/lesson_elegir_relaciones_y_recuperar_el_razonamiento_algebraico.json")
        val formulas = lesson("sistemas_lineales/lesson_formulas_con_datos_y_una_condicion_no_nula.json")
        assertRanges(block(recovery, 5, 3).getString("prompt"), "5/2")
        assertRanges(block(formulas, 2, 4).getJSONObject("feedback").getString("correct"),
            "(du − bv)/D", "(av − cu)/D")
        assertRanges(block(formulas, 5, 5).getString("prompt"), "2y/3", "−3x/4", "21/10")
    }

    @Test fun publishedAlgebraFractionsOwnBothOperandsAndTheSlash() {
        assertRanges("Una elección r debe cumplir 3r − 2 ≥ 5/2 y 5 − 2r ≥ 2 a la vez.", "5/2")
        assertRanges("El par queda forzado a x = (du − bv)/D e y = (av − cu)/D.",
            "(du − bv)/D", "(av − cu)/D")
        assertRanges("Resuelve x/2 + 2y/3 = 1 y −3x/4 + y/5 = 21/10.", "2y/3", "−3x/4", "21/10")
    }

    @Test fun numbersGroupsWhitespaceAndNegativeCoefficientsRetainExactSource() {
        assertRanges("Fracciones 5/2, 12.5/3.5, −3x/4 y (a + b) / 2.",
            "5/2", "12.5/3.5", "−3x/4", "(a + b) / 2")
        assertRanges("Un total (3/4 + 2)/D permanece completo.", "(3/4 + 2)/D")
        assertRanges("La resta 4 − 11/6 conserva su operador.", "11/6")
        assertRanges("El resultado (av − cu)/D, después continúa.", "(av − cu)/D")
    }

    @Test fun protectedCodeDatesPathsAndUrlsNeverBecomeFractions() {
        for (source in listOf(
            "Abre https://example.com/3x/4 y http://example.org/(av-cu)/D.",
            "Abre C:/datos/3x/4 o /datos/5/2 y carpeta/3x/4.",
            "Abre x/4, av/cu, (folder-name)/D y archivo/5/2.txt.",
            "Conserva 13/10/2026, 2026/10/13, 5/2.cpp y −3x/4.txt.",
            "Conserva `−3x/4` y ```(av − cu)/D``` literalmente.",
            "La fórmula explícita \\(5/2\\) o $5/2$ conserva su propio dueño.",
            "El número de versión C++23 no es una fracción."
        )) assertRanges(source)
    }

    @Test fun incompleteAndUnboundedOperandsDoNotCreatePartialRanges() {
        for (source in listOf("Texto (av − cu/D.", "Texto (av − cu)/Dato.",
            "Texto 3x/4/5.", "Texto 3x/4archivo.", "Texto palabra3x/4.",
            "Texto (" + "x+".repeat(80) + "y)/D.", "Texto (a\n−b)/D.")) assertRanges(source)
    }

    private fun assertRanges(source: String, vararg expected: String) {
        val ranges = MathInlineFractions.ranges(source)
        assertEquals(source, expected.toList(), ranges.map { source.substring(it.start, it.endExclusive) })
        ranges.forEach { range ->
            assertTrue(range.start >= 0 && range.start < range.endExclusive && range.endExclusive <= source.length)
            assertTrue(MathLatexParser.parse(source.substring(range.start, range.endExclusive)) is MathLatexParser.Result.Success)
        }
        ranges.zipWithNext().forEach { (left, right) -> assertTrue(left.endExclusive <= right.start) }
    }
}
