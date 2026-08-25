package com.pehredaar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PipelineLogicTest {

    @Test fun `identical frames score zero`() {
        val f = IntArray(64 * 64) { it % 256 }
        assertEquals(0f, meanAbsDiff(f, f.copyOf()), 0.001f)
    }

    @Test fun `mean absolute difference is the mean of the absolute differences`() {
        // Half the pixels differ by 10, half are identical -> mean is 5.
        val a = IntArray(100) { 0 }
        val b = IntArray(100) { if (it < 50) 10 else 0 }
        assertEquals(5f, meanAbsDiff(a, b), 0.001f)
    }

    @Test fun `difference is signed-safe in both directions`() {
        assertEquals(
            meanAbsDiff(intArrayOf(200, 10), intArrayOf(10, 200)),
            meanAbsDiff(intArrayOf(10, 200), intArrayOf(200, 10)),
            0.001f,
        )
    }

    @Test fun `parses the vlm json contract`() {
        val r = VisionResult.fromJson(
            """{"description":"गेट के पास एक आदमी","triggeredRules":[2,7],"severity":"alert"}"""
        )
        assertEquals("गेट के पास एक आदमी", r.description)
        assertEquals(listOf(2L, 7L), r.triggeredRules)
        assertEquals("alert", r.severity)
    }

    @Test fun `tolerates a fenced or chatty model reply`() {
        val r = VisionResult.fromJson(
            "Sure! ```json\n{\"description\":\"A person walks by.\",\"triggeredRules\":[],\"severity\":\"info\"}\n``` done"
        )
        assertEquals("A person walks by.", r.description)
        assertTrue(r.triggeredRules.isEmpty())
    }

    @Test fun `unknown severity falls back to info rather than reaching the UI`() {
        val r = VisionResult.fromJson("""{"description":"x","triggeredRules":[],"severity":"CATASTROPHE"}""")
        assertEquals("info", r.severity)
    }

    // ---- ask stage

    private fun event(desc: String, severity: String = "info", at: Long = 0L) =
        Event(id = at, timestamp = at, description = desc, ruleId = null, severity = severity, thumbnailPath = null)

    @Test fun `mock ask matches on a keyword and reports the newest hit first`() = runBlocking {
        val events = listOf(
            event("A person climbed over the boundary wall.", "alert", 2000),
            event("The yard is quiet.", "info", 1000),
        )
        val answer = MockLogQa().ask("did anyone climb the wall?", events)
        assertTrue(answer, answer.contains("1 matching"))
        assertTrue(answer, answer.contains("climbed over the boundary wall"))
    }

    @Test fun `a hindi question gets a hindi answer`() = runBlocking {
        val answer = MockLogQa().ask("कितने अलर्ट?", listOf(event("गेट के पास एक आदमी", "alert", 1000)))
        assertTrue(answer, answer.isHindi())
    }

    @Test fun `severity questions filter on severity, not on description words`() = runBlocking {
        val events = listOf(
            event("A person climbed the wall.", "alert", 3000),
            event("Someone is loitering.", "warn", 2000),
            event("The yard is quiet.", "info", 1000),
        )
        // No description contains the word "alert" — matching on text alone would find nothing.
        val english = MockLogQa().ask("how many alerts?", events)
        assertTrue(english, english.contains("1 matching alert"))

        val hindi = MockLogQa().ask("कितने अलर्ट?", events)
        assertTrue(hindi, hindi.isHindi() && hindi.contains("1"))
    }

    @Test fun `an empty log does not pretend to have an answer`() = runBlocking {
        assertTrue(MockLogQa().ask("anything?", emptyList()).contains("empty"))
    }

    @Test fun `no match says so instead of inventing an event`() = runBlocking {
        val answer = MockLogQa().ask("elephant", listOf(event("A person walks by.")))
        assertTrue(answer, answer.contains("Nothing in the last"))
    }

    @Test fun `hindi words survive tokenising instead of splitting at every matra`() = runBlocking {
        // "कितने" is a stopword and "अलर्ट" a severity word; if the tokeniser drops the combining
        // marks both shatter into fragments, nothing is recognised, and the query matches nothing.
        val events = listOf(event("A person climbed the wall.", "alert", 2000))
        val answer = MockLogQa().ask("कितने अलर्ट?", events)
        assertTrue(answer, answer.contains("1"))
        assertTrue(answer, answer.contains("climbed"))
    }

    @Test fun `the ask prompt carries the log and the question`() {
        val prompt = buildAskPrompt("what happened?", listOf(event("A person walks by.", "warn")))
        assertTrue(prompt.contains("[warn] A person walks by."))
        assertTrue(prompt.endsWith("what happened?"))
    }

    @Test fun `the vlm prompt carries rules verbatim, unparsed`() {
        val rule = Rule(id = 4, text = "गेट के पास कोई रुके तो बताओ")
        val prompt = buildPrompt(listOf(rule))
        assertTrue(prompt.contains("4: गेट के पास कोई रुके तो बताओ"))
    }
}
