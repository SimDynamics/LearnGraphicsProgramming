package com.learngraphicsprogramming.app.math

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import com.learngraphicsprogramming.app.narration.ReadingReplacementSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Local math typesetting. Span measurements use the TextView's current paint, including the
 * lesson text scale: fractions and scripts grow with the surrounding words and share a baseline.
 * This is deliberately a documented TeX subset, rather than a remote WebView or a full TeX engine.
 */
class MathTextView(context: Context) : com.learngraphicsprogramming.app.narration.ReadingTextView(context) {
    fun setMathContent(value: String, mode: String = "auto") = MathTextRenderer.apply(this, value, mode)
}

object MathTextRenderer {
    /** Display ownership of the formulas already typeset by this renderer. */
    internal fun formulaRanges(text: CharSequence): List<IntRange> {
        val rendered = text as? Spanned ?: return emptyList()
        return rendered.getSpans(0, rendered.length, MathFormulaSpan::class.java).map {
            rendered.getSpanStart(it) until rendered.getSpanEnd(it)
        }
    }

    private val numericFraction = Regex("[−-]?\\d+(?:[.,]\\d+)?/\\d+(?:[.,]\\d+)?")
    private val coordinates = Regex("\\([−-]?\\d+(?:[.,]\\d+)?,\\s*[−-]?\\d+(?:[.,]\\d+)?\\)")
    private val relation = Regex("[−-]?(?:\\d+(?:[.,]\\d+)?|[xyab])\\s*[≤≥<>≠=]\\s*[−-]?(?:\\d+(?:[.,]\\d+)?|[xyab])")
    private val equation = Regex("[xyab]\\s*[=≤≥<>≠]\\s*[−-]?(?:\\d+(?:[.,]\\d+)?[xyabπ]?|[xyabπ])(?:\\s*[+−-]\\s*(?:\\d+(?:[.,]\\d+)?[xyabπ]?|[xyabπ]))*")
    private val arithmetic = Regex("[−-]?\\d+(?:[.,]\\d+)?(?:\\s*[×÷+−]\\s*[−-]?\\d+(?:[.,]\\d+)?)+")
    private val power = Regex("[a-zA-Z0-9π]+(?:\\^[{]?[−-]?\\d+[}]?|[²³⁴⁵⁶⁷⁸⁹⁰¹]+)")
    private val wholeMath = Regex("[\\d\\s.,;:xyabnmπθ=<>≤≥≠+−*/×÷()\\[\\]{}|∪∩?²³⁴⁵⁶⁷⁸⁹⁰¹^_%-]+")
    private val integerEnumeration = Regex("""[−-]?[0-9]+(?:\s*,\s*[−-]?[0-9]+)*\s+y\s+[−-]?[0-9]+""")

    /** mode: text preserves prose; inline/block typeset the complete expression; auto recognizes
     * only explicit delimiters, unambiguous mathematical fragments and short whole expressions.
     * Use \\( ... \\), \\[ ... \\] or $ ... $ to author mathematics inside ordinary prose.
     */
    fun apply(view: TextView, value: String, mode: String = "auto") {
        view.text = render(value, mode)
        view.contentDescription = value.replace("\\(", "").replace("\\)", "")
            .replace("\\[", "").replace("\\]", "")
    }

    fun render(value: String, mode: String = "auto"): CharSequence {
        if (mode == "text" || value.isBlank()) return value
        val output = SpannableStringBuilder()
        fun appendMath(source: String, content: Boolean = false) {
            if (source.isBlank()) { output.append(source); return }
            val start = output.length
            output.append(source)
            val expression = parseExpression(source, content)
            output.setSpan(MathFormulaSpan(expression, MathReadingAtoms.flat(source, expression)), start, output.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun appendContent(source: String) {
            // TextView owns line breaks; every line still uses the common AST layout.
            source.split('\n').forEachIndexed { index, line ->
                if (index > 0) output.append('\n')
                appendMath(line, content = true)
            }
        }
        if (mode == "inline" || mode == "block") {
            var index = 0
            var contentStart = 0
            while (index < value.length) {
                val delimiters = delimitersAt(value, index)
                val end = delimiters?.let { value.indexOf(it.second, index + it.first.length) } ?: -1
                if (delimiters != null && end > index + delimiters.first.length) {
                    appendContent(value.substring(contentStart, index))
                    val display = delimiters.first == "\\[" || delimiters.first == "$$"
                    if (display && output.isNotEmpty() && output.last() != '\n') output.append('\n')
                    appendMath(value.substring(index + delimiters.first.length, end).let { if (display) it.trim() else it })
                    index = end + delimiters.second.length
                    if (display && index < value.length && value[index] != '\n') output.append('\n')
                    contentStart = index
                } else index++
            }
            appendContent(value.substring(contentStart))
            return output
        }
        val protected = MathTextSemantics.automaticMathExclusions(value)
        if (protected.isEmpty() && value.length <= 100 && wholeMath.matches(value.trim()) &&
            !integerEnumeration.matches(value.trim())) {
            appendMath(value)
            return output
        }
        val fractions = MathInlineFractions.ranges(value)
        var index = 0
        while (index < value.length) {
            val delimiters = delimitersAt(value, index)
            if (delimiters != null) {
                val end = value.indexOf(delimiters.second, index + delimiters.first.length)
                if (end > index + delimiters.first.length) {
                    val display = delimiters.first == "\\[" || delimiters.first == "$$"
                    if (display && output.isNotEmpty() && output.last() != '\n') output.append('\n')
                    appendMath(value.substring(index + delimiters.first.length, end).let { if (display) it.trim() else it })
                    index = end + delimiters.second.length
                    if (display && index < value.length && value[index] != '\n') output.append('\n')
                    continue
                }
            }
            val fraction = fractions.firstOrNull { it.start == index }
            if (fraction != null) {
                appendMath(value.substring(fraction.start, fraction.endExclusive))
                index = fraction.endExclusive
                continue
            }
            val protectedSource = protected.firstOrNull { it.start == index }
            if (protectedSource != null) {
                output.append(value, protectedSource.start, protectedSource.endExclusive)
                index = protectedSource.endExclusive
                continue
            }
            val candidate = listOf(numericFraction, coordinates, equation, relation, arithmetic, power).mapNotNull {
                it.matchAt(value, index)
            }.maxByOrNull { it.value.length }
            if (candidate != null && (index == 0 || !value[index - 1].isLetterOrDigit()) &&
                protected.none { it.start <= candidate.range.last && it.endExclusive > index } &&
                fractions.none { it.start <= candidate.range.last && it.endExclusive > index }) {
                appendMath(candidate.value)
                index = candidate.range.last + 1
            } else if (value[index] in "π≤≥≠×÷") {
                appendMath(value[index].toString())
                index++
            } else {
                output.append(value[index])
                index++
            }
        }
        return output
    }

    private fun delimitersAt(value: String, index: Int): Pair<String, String>? = when {
        value.startsWith("\\(", index) -> "\\(" to "\\)"
        value.startsWith("\\[", index) -> "\\[" to "\\]"
        value.startsWith("$$", index) -> "$$" to "$$"
        value[index] == '$' -> "$" to "$"
        else -> null
    }

    /** Legacy authored fractions and Unicode scripts remain supported by the shared AST engine. */
    internal fun parseExpression(source: String, content: Boolean = false): MathExpression.Sequence {
        // A coordinate separator is punctuation even without whitespace: (1,2) must not
        // become the decimal (1.2) when imported by the shared mathematical parser.
        val preservedCoordinates = coordinates.replace(source) { match -> match.value.replace(",", "\\mathrm{,}") }
        val fractions = numericFraction.replace(preservedCoordinates) { match ->
            val (top, bottom) = match.value.split('/', limit = 2)
            "\\frac{$top}{$bottom}"
        }
        val normalized = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹]+").replace(fractions) { match ->
            "^{${match.value.map { "⁰¹²³⁴⁵⁶⁷⁸⁹".indexOf(it) }.joinToString("")}}"
        }
        return when (val result = if (content) MathLatexParser.parseContent(normalized) else MathLatexParser.parse(normalized)) {
            is MathLatexParser.Result.Success -> result.expression
            is MathLatexParser.Result.Failure -> MathExpression.Sequence(listOf(MathExpression.Symbol(source)))
        }
    }
}

private class MathFormulaSpan(private val expression: MathExpression.Sequence,
                              private val readingAtoms: List<MathReadingAtoms.Atom>?) : ReplacementSpan(), ReadingReplacementSpan {
    override fun addReadingPath(path: Path, from: Int, to: Int, spanStart: Int, spanEnd: Int,
                                x: Float, baseline: Float, paint: Paint) {
        if (from >= to || from >= spanEnd || to <= spanStart) return
        val layout = MathExpressionLayout.create(expression, paint, paint.textSize)
        val atoms = readingAtoms
        if (atoms == null) {
            // Complex notation has one conservative source owner. Its real glyph geometry stays
            // intact while the ordinary words around it can continue to track independently.
            path.addRect(x, baseline + layout.ascent, x + layout.width, baseline + layout.descent, Path.Direction.CW)
            return
        }
        atoms.forEachIndexed { index, atom ->
            if (atom.start + spanStart < to && atom.endExclusive + spanStart > from) {
                layout.childBounds(emptyList(), index)?.let { bounds ->
                    path.addRect(x + bounds.left, baseline + bounds.top, x + bounds.right,
                        baseline + bounds.bottom, Path.Direction.CW)
                }
            }
        }
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val size = paint.textSize
        val metrics = MathExpressionLayout.create(expression, paint, size)
        fm?.let {
            it.ascent = min(it.ascent, kotlin.math.floor(metrics.ascent).toInt())
            it.top = min(it.top, it.ascent)
            it.descent = max(it.descent, ceil(metrics.descent).toInt())
            it.bottom = max(it.bottom, it.descent)
        }
        return ceil(metrics.width).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        MathExpressionLayout.create(expression, paint, paint.textSize).draw(canvas, Paint(paint), x, y.toFloat())
    }
}
