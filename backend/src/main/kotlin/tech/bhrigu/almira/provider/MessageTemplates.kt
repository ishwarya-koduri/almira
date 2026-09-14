package tech.bhrigu.almira.provider

/**
 * The words around a notification, per channel and language (docs/13 "What a
 * message says").
 *
 * Whatever caused a message — a reminder, a Still true? digest, an emergency
 * notice — supplies its title and body. This adds the two things every message
 * owes the person reading it: **why it came**, in one plain sentence, and the
 * quiet promise with where to change what they get. A message that cannot say
 * why it was sent should not be sent.
 *
 * What goes where:
 *  - **push**: the title, and the short reason as its text — never the caller's
 *    body. A push payload passes through Apple's or Google's servers and sits on
 *    a lock screen, and the body carries amounts and institutions
 *    (docs/providers/push.md).
 *  - **sms**: one line — the title and the short reason. DLT registers every
 *    SMS template, so this shape is what gets registered (docs/providers/sms.md).
 *  - **email**: the title as the subject; the body, the reason and the promise.
 *
 * **Languages.** English is written and reviewed. Telugu and Hindi are drafts,
 * kept here beside the English so a reviewer sees both, and marked
 * [Wording.needsReview]. A draft is never sent: someone whose language is
 * Telugu gets the English words until a native speaker has signed the draft
 * off and moved it into [REVIEWED]. The titles and bodies callers supply are
 * English today, as the server's other sentences are (docs/14).
 */
object MessageTemplates {

    /** The kinds of message, by what the person is being told about. */
    enum class Kind { REMINDER, STILL_TRUE, EMERGENCY, ACCOUNT, OTHER }

    /**
     * Messages that must reach a person whatever they have chosen: someone asking
     * for emergency access to their records, a change to their own account. They
     * are not paced by quiet hours or the daily limit and not stopped by a
     * switched-off channel — the same way a bank's security alert is not.
     */
    fun isEssential(template: String): Boolean = kindOf(template) in ESSENTIAL

    /** The SQL form of [isEssential], for the worker's "already told today?" check. */
    const val ESSENTIAL_SQL = "(o.template like 'emergency.%' or o.template like 'lifecycle.%')"

    fun kindOf(template: String): Kind = when {
        template.startsWith("reminder.") -> Kind.REMINDER
        template.startsWith("still_true.") -> Kind.STILL_TRUE
        template.startsWith("emergency.") -> Kind.EMERGENCY
        template.startsWith("lifecycle.") -> Kind.ACCOUNT
        else -> Kind.OTHER
    }

    data class Wording(
        val language: String,
        /** One sentence: why this person, why now. */
        val why: Map<Kind, String>,
        /** The same, short enough for one SMS line. */
        val whyShort: Map<Kind, String>,
        val promise: String,
        val needsReview: Boolean,
    )

    data class Composed(
        val subject: String,
        val text: String,
        /** The language actually used, which is English whenever the person's is still a draft. */
        val language: String,
    )

    private val ESSENTIAL = setOf(Kind.EMERGENCY, Kind.ACCOUNT)

    val ENGLISH = Wording(
        language = "en",
        why = mapOf(
            Kind.REMINDER to "You're getting this because you own or hold this record in Almira, and its date is coming up.",
            Kind.STILL_TRUE to "You're getting this because these are yours in Almira and nobody has confirmed them in a while. " +
                "Not a good time? Choose \"Ask me later\" in Almira and we'll wait a week.",
            Kind.EMERGENCY to "You're getting this because it's about who can reach your family's records if someone can't. " +
                "Messages like this always come, even in quiet hours.",
            Kind.ACCOUNT to "You're getting this because it's about your own Almira account. Messages like this always come.",
            Kind.OTHER to "You're getting this because of something in your Almira household.",
        ),
        whyShort = mapOf(
            Kind.REMINDER to "A date on your record is near.",
            Kind.STILL_TRUE to "Yours, not confirmed lately.",
            Kind.EMERGENCY to "About emergency access.",
            Kind.ACCOUNT to "About your account.",
            Kind.OTHER to "From your household.",
        ),
        promise = "Our quiet promise: at most one reminder a day, never a sales message, and every message says why it came. " +
            "Choose what you get in Almira, under Settings, Notifications.",
        needsReview = false,
    )

    /** Draft. Not sent until reviewed by a native speaker (see the class comment). */
    val TELUGU = Wording(
        language = "te",
        why = mapOf(
            Kind.REMINDER to "ఈ నమోదు Almiraలో మీది, దాని తేదీ దగ్గరపడుతోంది. అందుకే ఈ సందేశం.",
            Kind.STILL_TRUE to "ఇవి Almiraలో మీవి, కొంతకాలంగా వీటిని ఎవరూ ధృవీకరించలేదు. అందుకే ఈ సందేశం. " +
                "ఇప్పుడు వీలు కాదా? Almiraలో \"తర్వాత అడగండి\" ఎంచుకోండి, ఒక వారం ఆగుతాం.",
            Kind.EMERGENCY to "ఎవరైనా అందుబాటులో లేనప్పుడు మీ కుటుంబ నమోదులను ఎవరు చూడగలరో దీని గురించి. " +
                "ఇలాంటి సందేశాలు నిశ్శబ్ద సమయంలో కూడా వస్తాయి.",
            Kind.ACCOUNT to "ఇది మీ స్వంత Almira ఖాతా గురించి. ఇలాంటి సందేశాలు ఎప్పుడూ వస్తాయి.",
            Kind.OTHER to "మీ Almira కుటుంబంలో జరిగిన దాని వల్ల ఈ సందేశం.",
        ),
        whyShort = mapOf(
            Kind.REMINDER to "మీ నమోదు తేదీ దగ్గరలో ఉంది.",
            Kind.STILL_TRUE to "మీవి, ఈమధ్య ధృవీకరించలేదు.",
            Kind.EMERGENCY to "అత్యవసర ప్రాప్తి గురించి.",
            Kind.ACCOUNT to "మీ ఖాతా గురించి.",
            Kind.OTHER to "మీ కుటుంబం నుండి.",
        ),
        promise = "మా నిశ్శబ్ద హామీ: రోజుకు ఒక్క రిమైండర్ మించదు, అమ్మకాల సందేశాలు ఎప్పుడూ ఉండవు, ప్రతి సందేశం ఎందుకు వచ్చిందో చెబుతుంది. " +
            "Almiraలో సెట్టింగ్‌లు, నోటిఫికేషన్‌లలో మార్చుకోండి.",
        needsReview = true,
    )

    /** Draft. Not sent until reviewed by a native speaker (see the class comment). */
    val HINDI = Wording(
        language = "hi",
        why = mapOf(
            Kind.REMINDER to "यह रिकॉर्ड Almira में आपका है और इसकी तारीख़ पास आ रही है, इसलिए यह संदेश आया है।",
            Kind.STILL_TRUE to "ये Almira में आपके हैं और काफ़ी समय से किसी ने इनकी पुष्टि नहीं की है, इसलिए यह संदेश आया है। " +
                "अभी समय नहीं है? Almira में \"बाद में पूछें\" चुनें, हम एक हफ़्ता रुकेंगे।",
            Kind.EMERGENCY to "यह इस बारे में है कि किसी के न होने पर आपके परिवार के रिकॉर्ड तक कौन पहुँच सकता है। " +
                "ऐसे संदेश शांत समय में भी आते हैं।",
            Kind.ACCOUNT to "यह आपके अपने Almira खाते के बारे में है। ऐसे संदेश हमेशा आते हैं।",
            Kind.OTHER to "आपके Almira परिवार में हुई किसी बात की वजह से यह संदेश आया है।",
        ),
        whyShort = mapOf(
            Kind.REMINDER to "आपके रिकॉर्ड की तारीख़ पास है।",
            Kind.STILL_TRUE to "आपके, हाल में पुष्टि नहीं हुई।",
            Kind.EMERGENCY to "आपातकालीन पहुँच के बारे में।",
            Kind.ACCOUNT to "आपके खाते के बारे में।",
            Kind.OTHER to "आपके परिवार से।",
        ),
        promise = "हमारा शांत वादा: दिन में एक से ज़्यादा रिमाइंडर नहीं, कभी बिक्री का संदेश नहीं, और हर संदेश बताता है कि वह क्यों आया। " +
            "Almira में सेटिंग्स, सूचनाएँ में बदलें।",
        needsReview = true,
    )

    val ALL: List<Wording> = listOf(ENGLISH, TELUGU, HINDI)

    /** Languages whose wording may be sent. A draft joins only once a native speaker has reviewed it. */
    val REVIEWED: Set<String> = ALL.filterNot { it.needsReview }.map { it.language }.toSet()

    /** `te-IN` → `te`. Anything unknown, or not yet reviewed, is English. */
    fun wordingFor(locale: String?): Wording {
        val language = locale?.substringBefore('-')?.lowercase()
        return ALL.firstOrNull { it.language == language && it.language in REVIEWED } ?: ENGLISH
    }

    fun compose(template: String, title: String, body: String, channel: String, locale: String?): Composed =
        compose(template, title, body, channel, wordingFor(locale))

    /** With a given wording, drafts included — for the tests and for a reviewer's preview. */
    fun compose(template: String, title: String, body: String, channel: String, wording: Wording): Composed {
        val kind = kindOf(template)
        val why = wording.why.getValue(kind)
        val text = when (channel) {
            // The caller's body never; the short reason carries no amount or name.
            "push" -> wording.whyShort.getValue(kind)
            "sms" -> "Almira: $title. ${wording.whyShort.getValue(kind)}"
            else -> buildString {
                append(title).append("\n\n")
                if (body.isNotBlank()) append(body.trim()).append("\n\n")
                append(why).append("\n\n")
                append("— ").append(wording.promise)
            }
        }
        return Composed(subject = title, text = text, language = wording.language)
    }
}
