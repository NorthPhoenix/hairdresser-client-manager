package com.northphoenix.hairdresserclientmanager.domain

import java.text.Normalizer

/**
 * A name as a search compares it across alphabets. A Stylist who writes Russian has Clients saved
 * either way, as they were in the phone's contacts: «Ольга Иванова» and "Olga Smith". The search
 * finds a name typed in the other alphabet too, so «Ольга» finds "Olga" and "Julia" finds «Юлия»
 * ([Search]); a name in the alphabet of the search is found as it is typed, as before.
 *
 * [of] writes a name in one spelling made for comparing, never shown: Cyrillic letters in Latin
 * ones, and the usual ways of writing one Russian name in Latin letters made one ("Yulia",
 * "Julia" and "Iuliia"; "Alyona" and "Alena"; "Xenia" and "Ksenia"; "Natalya" and "Natalia").
 * It goes only as far as these: a doubled letter stays doubled (only "i", "y" and "j" in a row are
 * one "i"), so «Анна» does not find "Tatiana". Tested in `NameSpellingTest`.
 */
object NameSpelling {
    /** Each Cyrillic letter (lower case, "ё" read as "е") in Latin ones, as a passport or a phone contact writes it. */
    private val latin: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Ukrainian and Belarusian letters, which a Russian keyboard does not have but a contact may.
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g", 'ў' to "u",
    )

    /**
     * The spellings made one, replaced in this order in the name in Latin letters: a sound written
     * with one letter in one way and two in another ("x" and "ks", "ph" and "f") is written one way;
     * "c" is "s" before "e", "i" or "y" ("Cindy") and "k" otherwise (but "ch" stays); the "h" of
     * "John" and one ending a word after a vowel are silent ("Sarah"), and any other "h" that is not
     * the second letter of "ch", "sh", "zh" or "kh" is «х», "kh" ("Hannah", "Ahmed"); "ye" and "yo" before
     * a letter are "e", as they write «е» and «ё» ("Yelena", "Alyona", "Vasilyeva"), and so are "je"
     * and "jo" inside a word, but "Jo" starting a word stays ("John"); "y" and "j" are "i", and a run
     * of "i" is one ("iya" is "ia"), and "iu" after a consonant is "u", as «ю» is in "Ludmila". An
     * "ie" stays: in "Daniel" or "Natalie" it is not «е».
     */
    private val spellings: List<Pair<Regex, String>> = listOf(
        "dzh" to "j",
        "shch" to "sch",
        "ph" to "f",
        "th" to "t",
        "chr" to "kr",
        "ck" to "k",
        "x" to "ks",
        "w" to "v",
        "q" to "k",
        "tz" to "ts",
        "c(?=[eiy])" to "s",
        "c(?!h)" to "k",
        "ohn" to "on",
        "(?<=[aeiouy])h(?!\\p{L})" to "",
        "(?<![cszk])h" to "kh",
        "(?<=\\p{L})[yj][eo](?=\\p{L})|(?<!\\p{L})y[eo](?=\\p{L})" to "e",
        "[yj]" to "i",
        "i+" to "i",
        "(?<=[bcdfghklmnprstvz])iu" to "u",
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
        synchronized(spelled) { spelled[text] }?.let { return it }

        return spell(text).also { synchronized(spelled) { spelled[text] = it } }
    }

    /**
     * The names spelled so far, the most recently used last: a search spells every name of a list
     * again at each letter typed, and a list of contacts can have thousands.
     */
    private val spelled = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_SPELLED
    }

    private const val MAX_SPELLED = 10_000

    private fun spell(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('ё', 'е').filterNot(Char::isISOControl)
        val inLatin = buildString { folded.forEach { append(latin[it] ?: it) } }
        val plain = Normalizer.normalize(inLatin, Normalizer.Form.NFD).replace(marks, "").replace(apostrophes, "")

        return spellings.fold(plain.replace(separators, " ").trim()) { spelling, (from, to) -> spelling.replace(from, to) }
    }

    /**
     * A search as it looks for names in the other alphabet: one typed with a Cyrillic letter in the
     * names with a Latin one, and the other way round, by their spellings ([of]). It never finds a
     * name in its own alphabet that it does not hold as typed: «Илья» does not find «Лилия», though
     * "ilia" is in "lilia". In the other alphabet it is looked for from the start of a word of the
     * name, as names are typed: «Яна» finds "Yana Lee" but not "Diana", whose spelling holds "iana".
     * A search of fewer than two letters (a number has none), or spelled shorter than two letters
     * («Дж» is "i"), looks for nothing.
     */
    class Search(text: String) {
        private val spelled: String? = of(typedSoFar(text)).takeIf { it.length >= 2 && text.count(Char::isLetter) >= 2 }
        private val cyrillic = text.any(::isCyrillic)
        private val latin = text.any(::isLatin)

        /** Whether [name], written in the other alphabet (or partly), holds this search by its spelling. */
        fun finds(name: String): Boolean =
            spelled != null &&
                ((cyrillic && name.any(::isLatin)) || (latin && name.any(::isCyrillic))) &&
                " ${of(name)}".contains(" $spelled")
    }
}

/**
 * A search as its last letters will read once the word is typed on: a "y" or "j" it ends with after
 * a letter may start «ё», «ю» or «я» ("Aly…" on the way to "Alyona"), so it is left out for now, and
 * a "ye" or "yo" it ends with after a letter is «е» or «ё», as inside a word ("Alyo" is "Ale").
 */
private fun typedSoFar(text: String): String = text.replace(endingYeOrYo, "e").replace(endingY, "")

private val endingYeOrYo = Regex("(?<=\\p{L})[yjYJ][eoEO]$")
private val endingY = Regex("(?<=\\p{L})[yjYJ]$")

private fun isCyrillic(char: Char): Boolean = char.isLetter() && Character.UnicodeScript.of(char.code) == Character.UnicodeScript.CYRILLIC

private fun isLatin(char: Char): Boolean = char.isLetter() && Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN
