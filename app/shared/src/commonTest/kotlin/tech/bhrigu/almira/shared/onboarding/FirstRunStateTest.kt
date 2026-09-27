package tech.bhrigu.almira.shared.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The first run's answers, without a screen or a server (docs/03 §1).
 *
 * What is worth pinning here is not the form: it is the two places where an
 * answer means something other than itself, because both would be silent if
 * they were wrong. A household created as "just me" when the person said it was
 * for their mother would quietly be the wrong shape, and an empty name would be
 * sent as a blank rather than left out.
 */
class FirstRunStateTest {

    @Test
    fun aPersonWhoTapsStraightThroughGetsAPrivateAlmirahOfTheirOwn() {
        val untouched = FirstRunState()
        assertNull(untouched.problem, "nothing is required of somebody setting it up for themselves")
        assertEquals("just_me", untouched.mode)
        assertEquals("private", untouched.visibility)
    }

    @Test
    fun settingItUpForSomebodyElseIsAFamilyHouseholdWhateverTheNextAnswerSays() {
        val forAmma = FirstRunState(
            forWhom = SettingUpFor.SomeoneElse,
            personName = "Amma",
            tracking = Tracking.JustMe,
        )
        assertEquals(
            "family",
            forAmma.mode,
            "there are two people in it the moment it is for somebody else",
        )
        assertNull(forAmma.problem)
    }

    @Test
    fun theOnePersonWeCannotGuessIsAskedFor() {
        val noName = FirstRunState(forWhom = SettingUpFor.SomeoneElse)
        assertEquals("Tell us who you're setting this up for.", noName.problem)
        assertEquals("Tell us who you're setting this up for.", noName.copy(personName = "   ").problem)
        assertNull(noName.copy(personName = "Amma").problem)

        // And it is asked for only when it is theirs to answer.
        assertNull(FirstRunState(forWhom = SettingUpFor.Me, personName = "").problem)
    }

    @Test
    fun sharedByDefaultIsWhatTheHouseholdSees() {
        assertEquals("household", FirstRunState(startsAs = StartsAs.Shared).visibility)
        assertEquals("family", FirstRunState(tracking = Tracking.Family).mode)
    }
}
