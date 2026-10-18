package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonCreditsTest {
    private fun row(person: String, imdb: String, title: String?, date: String?, role: String, kind: String? = null) = buildString {
        append("""{"person":{"type":"uri","value":"http://www.wikidata.org/entity/$person"},""")
        append(""""imdb":{"type":"literal","value":"$imdb"},""")
        if (title != null) append(""""title":{"xml:lang":"en","type":"literal","value":"$title"},""")
        if (date != null) append(""""date":{"type":"literal","value":"$date"},""")
        if (kind != null) append(""""kind":{"type":"literal","value":"$kind"},""")
        append(""""role":{"type":"literal","value":"$role"}}""")
    }

    @Test
    fun creditsOfTheBestKnownPersonNewestFirst() {
        val json = """{"head":{},"results":{"bindings":[""" + listOf(
            row("Q1", "tt1160419", "Dune", "2021-09-03T00:00:00Z", "actor"),
            row("Q1", "tt1160419", "Dune", "2021-10-22T00:00:00Z", "actor"),
            row("Q1", "tt15239678", "Dune: Part Two", "2024-03-01T00:00:00Z", "actor"),
            row("Q1", "tt0816692", "Interstellar", "2014-11-07T00:00:00Z", "actor"),
            row("Q1", "tt9999999", null, null, "actor", kind = "series"),
            // A namesake with a single credit.
            row("Q2", "tt0000001", "Other", "1950-01-01T00:00:00Z", "actor"),
        ).joinToString(",") + "]}}"

        val credits = PersonCredits.parse(json)
        assertEquals(listOf("tt15239678", "tt1160419", "tt0816692", "tt9999999"), credits.map { it.imdbId })
        assertEquals("one entry per title, earliest release year", 2021, credits[1].year)
        assertEquals("series", credits.last().type)
        assertEquals("no English label: the id is shown", "tt9999999", credits.last().title)
    }

    @Test
    fun directingAndActingAreSeparateCredits() {
        val json = """{"results":{"bindings":[""" +
            row("Q1", "tt1", "Film", "2020-01-01T00:00:00Z", "actor") + "," +
            row("Q1", "tt1", "Film", "2020-01-01T00:00:00Z", "director") + "]}}"
        assertEquals(setOf("actor", "director"), PersonCredits.parse(json).map { it.role }.toSet())
    }

    @Test
    fun namesAreQuotedSafelyAndBadAnswersGiveNothing() {
        assertTrue(PersonCredits.query("Dwayne \"The Rock\" Johnson").contains("""mwapi:search "Dwayne \"The Rock\" Johnson""""))
        assertEquals(emptyList<Credit>(), PersonCredits.parse("<html>Too many requests</html>"))
    }
}
