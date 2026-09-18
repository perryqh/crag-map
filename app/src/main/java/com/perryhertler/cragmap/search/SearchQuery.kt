package com.perryhertler.cragmap.search

private val GRADE_REGEX = Regex(
    """^(?:5\.\d{1,2}[abcdABCD]?|V\d{1,2}[+-]?)$""",
)

/**
 * True when the whole query looks like a YDS or V-scale grade (e.g. 5.8, 5.10a, V3).
 * Used to prefer grade matches in search without a separate filter UI.
 */
fun looksLikeGradeQuery(query: String): Boolean =
    GRADE_REGEX.matches(query.trim())
