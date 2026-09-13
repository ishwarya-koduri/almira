package tech.bhrigu.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The key-holder guidance docs/23 asks for, where the app shows it. */
class WhereAndWhoWordingTest {

    @Test
    fun theKeyHolderLineAsksForARoleAndSaysAlmiraCannotReadIt() {
        val help = WhereAndWhoWording.helpFor("key_holder")!!
        listOf("“Amma”", "“the CA”", "not a full name", "phone number", "End-to-end encrypted", "Almira can't read it")
            .forEach { assertTrue(it in help, "missing: $it") }
    }

    @Test
    fun theLocationLineHasItsOwnGuidanceAndOtherFieldsHaveNone() {
        assertTrue("Almira can't read it" in WhereAndWhoWording.helpFor(" original_location ")!!)
        assertNull(WhereAndWhoWording.helpFor("policy_number"))
        assertEquals("Key or papers with", WhereAndWhoWording.labelFor("key_holder"))
        assertEquals("policy_number", WhereAndWhoWording.labelFor("policy_number"))
    }
}
