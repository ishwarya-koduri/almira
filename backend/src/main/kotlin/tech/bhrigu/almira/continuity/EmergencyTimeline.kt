package tech.bhrigu.almira.continuity

import java.time.Duration
import java.time.Instant

/**
 * Emergency access as a dated picture rather than a paragraph (X-41).
 *
 * "Ravi asks → you're told → 7 days to say no → he sees the family plan, never
 * your private entries." The same five steps for a request that exists and for
 * one that is only being imagined while someone is named, so the picture a
 * person agreed to is the picture they later see.
 *
 * Pure: every date and name comes in, and nothing is read here.
 */
object EmergencyTimeline {

    fun forRequest(
        status: String,
        requestedByMe: Boolean,
        requesterName: String?,
        subjectName: String?,
        requestedAt: Instant,
        unlockAt: Instant,
        expiresAt: Instant,
        stoppedAt: Instant?,
        subjectActive: Boolean,
    ): List<EmergencyTimelineStep> {
        val asker = if (requestedByMe) "You" else requesterName ?: "Someone"
        val subject = subjectName ?: "the person it concerns"
        val days = Duration.between(requestedAt, unlockAt).toDays().coerceAtLeast(1)
        val stopped = status == "vetoed" || status == "withdrawn"

        return listOf(
            EmergencyTimelineStep(
                key = "asked",
                title = "$asker asked",
                detail = if (requestedByMe) "You asked to see $subject's family plan." else "$asker asked to see $subject's family plan.",
                at = requestedAt,
                state = "done",
            ),
            EmergencyTimelineStep(
                key = "told",
                title = if (requestedByMe) "$subject was told" else "You were told",
                detail = "Straight away, the moment it was asked.",
                at = requestedAt,
                state = "done",
            ),
            EmergencyTimelineStep(
                key = "say_no",
                title = "${dayWord(days)} to say no",
                detail = when {
                    status == "vetoed" -> "$subject said no. Nothing was opened."
                    status == "withdrawn" -> "Withdrawn before it opened. Nothing was opened."
                    subjectActive -> "$subject has used Almira since, so it stays shut."
                    else -> "$subject can stop it at any time until then, with one tap."
                },
                at = stoppedAt ?: unlockAt,
                state = when {
                    stopped -> "done"
                    status == "waiting" -> "now"
                    else -> "done"
                },
            ),
            EmergencyTimelineStep(
                key = "opens",
                title = if (requestedByMe) "You see the family plan" else "$asker sees the family plan",
                detail = "Only what is marked for the family. Never the private entries left out of it.",
                at = unlockAt,
                state = when {
                    stopped -> "skipped"
                    status == "open" -> "now"
                    status == "ended" -> "done"
                    else -> "next"
                },
            ),
            EmergencyTimelineStep(
                key = "closes",
                title = "It closes by itself",
                detail = "After ${Duration.between(unlockAt, expiresAt).toDays()} days, with nothing left switched on.",
                at = expiresAt,
                state = when {
                    stopped -> "skipped"
                    status == "ended" -> "done"
                    else -> "next"
                },
            ),
        )
    }

    /** As if [trustedName] asked at [now]. Every step still to come. */
    fun preview(
        trustedName: String,
        now: Instant,
        unlockAt: Instant,
        expiresAt: Instant,
        waitDays: Int,
    ): List<EmergencyTimelineStep> = listOf(
        EmergencyTimelineStep("asked", "$trustedName asks", "Only if they ever need to. Nothing changes today.", now, "next"),
        EmergencyTimelineStep("told", "You're told", "Straight away, the moment they ask.", now, "next"),
        EmergencyTimelineStep(
            "say_no", "${dayWord(waitDays.toLong())} to say no",
            "One tap stops it. Using Almira at all in that time keeps it shut too.", unlockAt, "next",
        ),
        EmergencyTimelineStep(
            "opens", "$trustedName sees the family plan",
            "Only what is marked for the family. Never your private entries left out of it.", unlockAt, "next",
        ),
        EmergencyTimelineStep(
            "closes", "It closes by itself",
            "After ${Duration.between(unlockAt, expiresAt).toDays()} days, with nothing left switched on.", expiresAt, "next",
        ),
    )

    fun willSee(included: Int, debts: Int, paperwork: Int): List<String> = buildList {
        add(
            if (included == 0) "Nothing is marked for the family plan yet."
            else "${plural(included, "thing", "things")} marked for the family plan, with how to claim each",
        )
        if (debts > 0) add("${plural(debts, "loan", "loans")}, so nobody inherits an asset without its debt")
        if (paperwork > 0) add("Your will and other paperwork, and who the executor is")
        add("Who to call")
    }

    fun neverSee(leftOut: Int): List<String> = buildList {
        if (leftOut > 0) add("The ${plural(leftOut, "entry", "entries")} you left out of the family plan")
        add("Anything you sealed, unless you made a recovery copy for them")
        add("Anything at all while they wait, or after you say no")
    }

    private fun dayWord(days: Long) = if (days == 1L) "1 day" else "$days days"

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
