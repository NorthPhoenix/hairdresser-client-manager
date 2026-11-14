package com.northphoenix.hairdresserclientmanager.domain

import java.text.Normalizer

/**
 * A name as a search compares it across alphabets. A Stylist who writes Russian has Clients saved
 * either way, as they were in the phone's contacts: «Ольга Иванова» and "Olga Smith". The search
 * finds a name typed in the other alphabet too, so «Ольга» finds "Olga" and "Julia" finds «Юлия».
 *
 * [of] writes a name in one spelling made for comparing, never shown: Cyrillic letters in Latin
 * ones, and the usual ways of writing one Russian name in Latin letters made one ("Yulia",
 * "Julia" and "Iuliia"; "Alyona" and "Alena"; "Xenia" and "Ksenia"; "Natalya" and "Natalia").
 * It goes only as far as these: a doubled letter stays doubled, so «Анна» does not find «Татьяна».
 * Tested in `NameSpellingTest`.
 */
object NameSpelling {
    /** Each Cyrillic letter (lower case, "ё" read as "е") in Latin ones, as a passport or a phone contact writes it. */
    private val latin: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Ukrainian and Belarusian letters, which a Russian keyboard does not have but a contact may.
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g", 'ў' to "u",
    )

    /**
     * The spellings made one, replaced in this order in the name in Latin letters: a sound written
     * with one letter in one way and two in another ("h" and "kh", "ks" and "x") is written one way,
     * "c" is "k" (but "ch" stays), "e" or "o" after "y", "j" or "i" is "e" (as "ye" and "yo" write
     * «е» and «ё»), "y" and "j" are "i", and a run of "i" is one ("iya" is "ia").
     */
    private val spellings: List<Pair<Regex, String>> = listOf(
        "dzh" to "j",
        "shch" to "sch",
        "kh" to "h",
        "ph" to "f",
        "th" to "t",
        "chr" to "kr",
        "ck" to "k",
        "x" to "ks",
        "w" to "v",
        "q" to "k",
        "tz" to "ts",
        "c(?!h)" to "k",
        "[yji]e|[yj]o" to "e",
        "[yj]" to "i",
        "i+" to "i",
    ).map { (from, to) -> Regex(from) to to }

    private val apostrophes = Regex("['’ʼ`´]")
    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /**
     * [text] in the spelling a search compares: lower case, in Latin letters without accents, the
     * spellings above made one, and anything but letters and digits one space. An apostrophe, which
     * may write the soft sign ("Dar'ya"), is nothing.
     */
    fun of(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('ё', 'е').filterNot(Char::isISOControl)
        val inLatin = buildString { folded.forEach { append(latin[it] ?: it) } }
        val plain = Normalizer.normalize(inLatin, Normalizer.Form.NFD).replace(marks, "").replace(apostrophes, "")

        return spellings.fold(plain.replace(separators, " ").trim()) { spelled, (from, to) -> spelled.replace(from, to) }
    }

    /**
     * [search] in the spelling of [of], to look for in the names spelled so; null for a search
     * without a letter: a number is found as it is typed, or by its digits.
     */
    fun ofSearch(search: String): String? = of(search).takeIf { it.isNotEmpty() && search.any(Char::isLetter) }
}
