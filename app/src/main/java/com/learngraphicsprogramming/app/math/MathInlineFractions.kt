package com.learngraphicsprogramming.app.math

/** Complete slash fractions for automatic inline layout. Source offsets and text stay intact.
 * This bounded recognizer supplements the existing numeric detector; it is not a new parser
 * for answers or a general interpretation of words and paths as algebra.
 */
internal object MathInlineFractions {
    data class Range(val start: Int, val endExclusive: Int)

    private const val NUMBER = "[−-]?\\d+(?:[.,]\\d+)?"
    private val number = Regex(NUMBER)
    private val coefficient = Regex("$NUMBER(?:\\p{L})?")
    private val denominator = Regex("(?:$NUMBER|\\p{L})")
    private val longWord = Regex("\\p{L}{3,}")
    private val groupedCharacters = Regex("[\\p{L}\\d\\s.,+−*/×÷·()\\-]+")
    private const val MAX_OPERAND = 128

    fun ranges(source: String): List<Range> {
        val exclusions = MathTextSemantics.automaticMathExclusions(source)
        val found = mutableListOf<Range>()
        source.indices.filter { source[it] == '/' }.forEach { slash ->
            val start = numeratorStart(source, slash) ?: return@forEach
            var denominatorStart = slash + 1
            while (denominatorStart < source.length && source[denominatorStart] in " \t") denominatorStart++
            val bottom = denominator.matchAt(source, denominatorStart) ?: return@forEach
            val end = bottom.range.last + 1
            if (!boundaryBefore(source, start) || !boundaryAfter(source, end)) return@forEach
            val numerator = source.substring(start, slash).trimEnd()
            if (numerator.startsWith('(') &&
                (!groupedCharacters.matches(numerator) || longWord.containsMatchIn(numerator))) return@forEach
            val expression = (MathLatexParser.parse(source.substring(start, end)) as?
                MathLatexParser.Result.Success)?.expression ?: return@forEach
            if (!simple(expression)) return@forEach
            if (exclusions.any { excluded ->
                    excluded.start < end && excluded.endExclusive > start &&
                        !isAlgebraicSuffix(source, start, slash, end, excluded)
                }) return@forEach
            found += Range(start, end)
        }
        // A grouped numerator can contain a smaller numeric fraction. Its outer span owns
        // the complete source once; overlapping ReplacementSpans would not be safe layout.
        return buildList {
            found.sortedWith(compareBy<Range> { it.start }.thenByDescending { it.endExclusive })
                .forEach { range -> if (lastOrNull()?.endExclusive?.let { it > range.start } != true) add(range) }
        }
    }

    private fun numeratorStart(source: String, slash: Int): Int? {
        var end = slash
        while (end > 0 && source[end - 1] in " \t") end--
        if (end == 0) return null
        val minimum = maxOf(0, end - MAX_OPERAND)
        if (source[end - 1] == ')') {
            var depth = 0
            for (index in end - 1 downTo minimum) {
                when (source[index]) {
                    ')' -> depth++
                    '(' -> if (--depth == 0) return index
                    '\n', '\r' -> return null
                }
            }
            return null
        }
        val prefix = source.substring(minimum, end)
        return coefficient.findAll(prefix).lastOrNull { it.range.last == prefix.lastIndex }
            ?.range?.first?.plus(minimum)
    }

    private fun boundaryBefore(source: String, start: Int): Boolean = start == 0 ||
        source[start - 1].let { !it.isLetterOrDigit() && it !in "_./\\" }

    private fun boundaryAfter(source: String, end: Int): Boolean {
        val next = source.getOrNull(end) ?: return true
        if (next.isLetterOrDigit() || next in "_/\\") return false
        // A decimal point or filename suffix is part of the token, not sentence punctuation.
        return next != '.' || source.getOrNull(end + 1)?.let { !it.isLetterOrDigit() && it != '_' } != false
    }

    private fun simple(node: MathExpression): Boolean = when (node) {
        is MathExpression.Sequence -> node.children.isNotEmpty() && node.children.all(::simple)
        is MathExpression.Number, is MathExpression.Variable -> true
        is MathExpression.Operator -> node.value in setOf("+", "-", "−", "*", "/", "×", "÷", "·")
        is MathExpression.Group -> simple(node.content)
        else -> false
    }

    /** The old path detector also returns x/4 inside 3x/4, or /D after (av − cu).
     * Only those suffixes of a complete, parsed mathematical operand can be absorbed.
     * A surrounding URL, code span, date, drive, directory or filename remains excluded.
     */
    private fun isAlgebraicSuffix(source: String, start: Int, slash: Int, end: Int,
                                 excluded: MathTextSemantics.Range): Boolean {
        if (excluded.start < start || excluded.endExclusive < end) return false
        val tail = source.substring(end, excluded.endExclusive)
        if (tail.length > 3 || tail.any { it !in ".,;:!?)]}" }) return false
        if (source[start] == '(') return excluded.start == slash
        return excluded.start == slash - 1 && source[excluded.start].isLetter() &&
            number.matches(source.substring(start, excluded.start))
    }
}
