package com.example.data.storage

/**
 * Utility for cleaning audiobook titles and author names so that only the pure book title
 * (without author name, release year, narrator/reader notes, or technical format tags)
 * is displayed in the Library and Player screens.
 */
object BookTitleCleaner {

    private val BRACKETED_META_REGEX = Regex(
        """[\(\[\{][^)\]\}]*?(?:\b(?:18|19|20)\d{2}\b|читает|чтец|диктор|озвуч|исполн|аудиокниг|audiobook|mp3|m4b|m4a|flac|wav|amr|kbps|кбит|битрейт|лиценз|издан|выпуск|г\.|год)[^)\]\}]*?[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )

    private val PURE_YEAR_BRACKETS_REGEX = Regex(
        """[\(\[\{]\s*(?:18|19|20)\d{2}(?:\s*[-–—/]\s*(?:18|19|20)?\d{2,4})?\s*(?:г\.?|год[а]?)?\s*[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )

    private val NARRATOR_TAIL_REGEX = Regex(
        """(?:\s*[-–—•,|/]\s*|\s+)(?:читает|чтец|диктор|озвучивает|озвучка|исполняет|читают|текст читает)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private val LEADING_YEAR_WITH_SEP_REGEX = Regex(
        """^\s*(?:18|19|20)\d{2}\s*(?:г\.?|год[а]?)?\s*[-–—._:/]+\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_YEAR_WITH_SEP_REGEX = Regex(
        """^(.+?)\s*(?:[-–—.,/]\s*(?:18|19|20)\d{2}\s*(?:г\.?|год[а]?)?|\s+(?:18|19|20)\d{2}\s*(?:г\.|год[а]?))\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_PLAIN_YEAR_REGEX = Regex(
        """^(.+?)\s+(19\d{2}|20[0-2]\d)\s*$"""
    )

    private val PRESERVE_TRAILING_NUMBER_PREFIXES = setOf(
        "метро", "metro", "одиссея", "odyssey", "уловка", "catch", "фаренгейт", "fahrenheit", "бегущий", "квартира", "комната", "объект", "сектор"
    )

    private val LEADING_INDEX_REGEX = Regex("""^\d{1,3}\s*[-_–—.)]\s*(.+)$""")

    private val FORMAT_SUFFIX_REGEX = Regex(
        """\s*[-–—]?\s*\b(?:mp3|m4b|m4a|wav|amr|audiobook|аудиокнига|fb2|epub)\b\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cleans a book title by removing:
     * - Narrator / reader annotations ("Читает ...", "[Чтец ...]")
     * - Release / publication years ("(2019)", "[1998 г.]", "- 2021", "2020 г.")
     * - Audio format / bitrate tags ("[MP3 192kbps]", "(M4B)")
     * - Author name prefix or suffix when it duplicates [author]
     * - Leading sequence numbers ("01. ", "01 - ")
     */
    fun cleanBookTitle(rawTitle: String, author: String = ""): String {
        if (rawTitle.isBlank()) return "Аудиокнига"

        var s = rawTitle
            .replace('_', ' ')
            .replace(Regex("""\.(mp3|m4b|m4a|wav|amr)$""", RegexOption.IGNORE_CASE), "")
            .trim()

        // 1. Strip bracketed/parenthesized years, narrator notes, and technical tags
        s = PURE_YEAR_BRACKETS_REGEX.replace(s, " ").trim()
        s = BRACKETED_META_REGEX.replace(s, " ").trim()

        // 2. Strip any unbracketed "Читает ..." / "Чтец ..." tail
        s = NARRATOR_TAIL_REGEX.replace(s, "").trim()

        // 3. Strip leading index if followed by real text (e.g. "01. Сияние" -> "Сияние")
        LEADING_INDEX_REGEX.find(s)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            s = it
        }

        // 4. Strip trailing format suffix
        s = FORMAT_SUFFIX_REGEX.replace(s, "").trim()

        // 5. Strip author name from title FIRST before unbracketed year stripping
        // so that "Джордж Оруэлл - 1984" strips the author "Джордж Оруэлл" and preserves "1984"
        val cleanedAuthor = cleanAuthorName(author)
        val hasKnownAuthor = cleanedAuthor.isNotBlank() && !isGenericAuthor(cleanedAuthor)
        if (hasKnownAuthor) {
            s = removeAuthorFromTitle(s, cleanedAuthor)
        }

        // 6. Strip bracketed/narrator tags again in case author removal exposed them
        s = PURE_YEAR_BRACKETS_REGEX.replace(s, " ").trim()
        s = BRACKETED_META_REGEX.replace(s, " ").trim()
        s = NARRATOR_TAIL_REGEX.replace(s, "").trim()

        // 7. Strip leading or trailing year separated by punctuation (e.g. "2019 - Сияние" or "Сияние - 2019")
        LEADING_YEAR_WITH_SEP_REGEX.find(s)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { candidate ->
            s = if (hasKnownAuthor) removeAuthorFromTitle(candidate, cleanedAuthor) else candidate
        }
        TRAILING_YEAR_WITH_SEP_REGEX.find(s)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { candidate ->
            // Do not strip "- 1984" if the remaining prefix is just the author name
            val authorTokens = tokenizeWords(cleanedAuthor)
            if (!hasKnownAuthor || !segmentMatchesAuthor(candidate, authorTokens)) {
                s = candidate
            }
        }

        // 8. Strip trailing plain release year (e.g. "Сияние 2019") unless part of known title like "Метро 2033"
        TRAILING_PLAIN_YEAR_REGEX.find(s)?.let { match ->
            val prefix = match.groupValues[1].trim()
            val lastWord = prefix.substringAfterLast(' ').lowercase()
            if (prefix.isNotBlank() && lastWord !in PRESERVE_TRAILING_NUMBER_PREFIXES) {
                s = prefix
            }
        }

        // 9. Second author check in case year removal exposed "Author - Title" (e.g. "[2018] Пикник на обочине - Стругацкие")
        if (hasKnownAuthor) {
            s = removeAuthorFromTitle(s, cleanedAuthor)
        }

        // Collapse multiple spaces and trim stray separators
        s = s.replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '–', '—', '.', ',', ':', ';', '/', '|', '•')

        return s.ifBlank { rawTitle.trim() }
    }

    /**
     * Cleans an author string by removing "Читает ...", years, brackets, and folder prefixes.
     */
    fun cleanAuthorName(rawAuthor: String): String {
        if (rawAuthor.isBlank()) return "Неизвестный автор"
        var s = rawAuthor
            .replace('_', ' ')
            .removePrefix("Папка:")
            .trim()

        if (s.equals("Корневая", ignoreCase = true) ||
            s.equals("Выбранная папка", ignoreCase = true) ||
            s.startsWith("Аудиофайл (", ignoreCase = true) ||
            s.equals("<unknown>", ignoreCase = true)
        ) {
            return "Неизвестный автор"
        }

        // If path like "Стивен Кинг/Сияние", take the first folder segment (the author folder)
        if (s.contains('/')) {
            val firstSeg = s.split('/').map { it.trim() }.firstOrNull { it.isNotBlank() }
            if (!firstSeg.isNullOrBlank()) {
                s = firstSeg
            }
        }

        s = PURE_YEAR_BRACKETS_REGEX.replace(s, " ").trim()
        s = BRACKETED_META_REGEX.replace(s, " ").trim()
        s = NARRATOR_TAIL_REGEX.replace(s, "").trim()
        s = s.replace(Regex("""^\[.*?\]\s*"""), "")
            .replace(Regex("""\s*\[.*?\]$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '–', '—', '.', ',', ':', ';', '/', '|', '•')

        return s.ifBlank { "Неизвестный автор" }
    }

    private fun isGenericAuthor(author: String): Boolean {
        val lower = author.lowercase().trim()
        return lower.isBlank() ||
            lower == "неизвестный автор" ||
            lower.startsWith("папка") ||
            lower.startsWith("аудиофайл") ||
            lower == "корневая" ||
            lower == "выбранная папка" ||
            lower == "память телефона"
    }

    private fun removeAuthorFromTitle(title: String, author: String): String {
        val authorTokens = tokenizeWords(author)
        if (authorTokens.isEmpty()) return title

        // 1. Check dash-separated parts: e.g. "Стивен Кинг - Сияние" or "Сияние - Стивен Кинг"
        val dashParts = title.split(Regex("""\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (dashParts.size >= 2) {
            val firstMatchesAuthor = segmentMatchesAuthor(dashParts.first(), authorTokens)
            val lastMatchesAuthor = segmentMatchesAuthor(dashParts.last(), authorTokens)
            if (firstMatchesAuthor && dashParts.size >= 2) {
                val remainder = dashParts.drop(1).joinToString(" - ").trim()
                if (remainder.isNotBlank()) return remainder
            }
            if (lastMatchesAuthor && dashParts.size >= 2) {
                val remainder = dashParts.dropLast(1).joinToString(" - ").trim()
                if (remainder.isNotBlank()) return remainder
            }
        }

        // 2. Check dot or colon separated prefix: e.g. "Стивен Кинг. Сияние" or "Кинг Стивен: Сияние"
        val punctMatch = Regex("""^([^.:]+?)\s*[.:]\s+(.+)$""").find(title)
        if (punctMatch != null) {
            val prefix = punctMatch.groupValues[1].trim()
            val rest = punctMatch.groupValues[2].trim()
            if (rest.isNotBlank() && segmentMatchesAuthor(prefix, authorTokens)) {
                return rest
            }
        }

        // 3. Direct case-insensitive prefix/suffix with separator
        val escapedAuthor = Regex.escape(author)
        val directPrefix = Regex("""^$escapedAuthor\s*[-–—.:/]+\s*(.+)$""", RegexOption.IGNORE_CASE)
        directPrefix.find(title)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        val directSuffix = Regex("""^(.+?)\s*[-–—.:/]+\s*$escapedAuthor$""", RegexOption.IGNORE_CASE)
        directSuffix.find(title)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }

        return title
    }

    private fun segmentMatchesAuthor(segment: String, authorTokens: Set<String>): Boolean {
        val segTokens = tokenizeWords(segment)
        if (segTokens.isEmpty()) return false

        // Exact token set match (handles "Стивен Кинг" == "Кинг Стивен")
        if (segTokens == authorTokens) return true

        // If all tokens in segment (with length >= 3) are in authorTokens, or vice versa
        val longAuthorTokens = authorTokens.filter { it.length >= 3 }.toSet()
        val longSegTokens = segTokens.filter { it.length >= 3 }.toSet()
        if (longAuthorTokens.isNotEmpty() && longSegTokens.isNotEmpty()) {
            // e.g. "А. Стругацкий" vs "Братья Стругацкие" or "М. Булгаков" vs "Михаил Булгаков"
            if (longSegTokens.all { segWord ->
                    longAuthorTokens.any { authWord ->
                        authWord == segWord ||
                            (authWord.length >= 5 && segWord.length >= 5 && authWord.take(5) == segWord.take(5))
                    }
                } && segTokens.size <= authorTokens.size + 2
            ) {
                return true
            }
        }
        return false
    }

    private fun tokenizeWords(text: String): Set<String> {
        return Regex("""[\p{L}\p{N}]+""")
            .findAll(text.lowercase().replace('ё', 'е'))
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()
    }
}
