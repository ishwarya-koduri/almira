/* =============================================================================
   Language (docs/14).

   Three languages, chosen because of who this is for: English, Telugu and
   Hindi. The switch is instant and remembered per browser.

   Two rules the rest of the client follows:

   1. **Numbers stay Indian-grouped in every language.** ₹1,76,875 is not an
      English convention, it is how the amount is written here, and rendering it
      as 176,875 in Telugu would be a mistranslation of the money.

   2. **The server's own sentences arrive in English for now** — error messages,
      the disclaimers, the notes explaining a missing return. They are written
      once, on the server, so a phone and a PDF cannot disagree, and moving them
      would mean sending a locale with every request. docs/14 says which strings
      those are and what it would take.

   A missing key falls back to English rather than showing the key: a screen in
   two languages is confusing, and a screen showing `home.attention.title` is
   broken.
   ============================================================================= */

const STORAGE_KEY = "almira.language";

export const LANGUAGES = [
  { code: "en", label: "English", native: "English" },
  { code: "te", label: "Telugu", native: "తెలుగు" },
  { code: "hi", label: "Hindi", native: "हिन्दी" },
];

const messages = {
  en: {
    "app.name": "Almira",
    "app.add": "＋ Add",
    "app.close": "Close",
    "app.save": "Save",
    "app.cancel": "Cancel",
    "app.remove": "Remove",
    "app.loading": "Just a moment…",
    "app.somethingWrong": "Something went wrong.",

    "nav.home": "Home",
    "nav.investments": "Investments",
    "nav.liabilities": "Owed",
    "nav.accounts": "Accounts",
    "nav.goals": "Goals",
    "nav.tax": "Tax",
    "nav.reports": "Reports",
    "nav.family": "Family",
    "nav.continuity": "For my family",
    "nav.settings": "Settings",

    "home.netWorth": "True net worth",
    "home.assets": "Assets",
    "home.owed": "Owed",
    "home.holdings": "Holdings",
    "home.loans": "Loans",
    "home.scope.me": "Me",
    "home.empty.title": "Nothing recorded yet",
    "home.empty.body": "Add your first holding — a fixed deposit or some gold takes about twenty seconds.",
    "home.empty.action": "＋ Add your first thing",
    "home.attention": "Worth a look",
    "home.upcoming": "Coming up",
    "home.whereItSits": "Where it sits",
    "home.whoseItIs": "Whose it is",

    "continuity.title": "For my family",
    "continuity.intro": "What exists, where it is, and who to call — if you're not there to explain it.",
    "continuity.included": "Included",
    "continuity.excluded": "left out on purpose",
    "continuity.print": "Print the handbook",
    "continuity.howToClaim": "How your family claims this",
    "continuity.nominee": "Nominee",
    "continuity.noNominee": "No nominee recorded",
    "continuity.keptAt": "Kept at",
    "continuity.call": "Call",
    "continuity.steps": "The steps",
    "continuity.documents": "What they'll ask for",
    "continuity.ready": "You have this",
    "continuity.notReady": "Not recorded yet",

    "estate.title": "Wills and paperwork",
    "estate.add": "＋ Record a document",
    "estate.location": "The original is",
    "estate.executor": "Executor",
    "estate.beneficiaries": "Who inherits",
    "estate.mismatch.title": "Nominee and will disagree",
    "estate.mismatch.explain": "A nominee receives the money; an heir inherits it. Worth checking that this is deliberate.",
    "estate.empty": "Nothing recorded. A will nobody can find is a will that does not exist.",

    "contacts.title": "People who help",
    "contacts.add": "＋ Add someone",
    "contacts.empty": "The CA, the agent, the lawyer who holds the will — the people your family will need to call.",

    "sharing.title": "Shared links",
    "sharing.create": "＋ Share something",
    "sharing.expires": "Expires",
    "sharing.views": "Opened",
    "sharing.revoke": "Withdraw",
    "sharing.copied": "Link copied.",
    "sharing.empty": "Nothing shared. A link shows one slice, for a while, and can be withdrawn at any time.",

    "emergency.title": "If you can't be reached",
    "emergency.trusted": "Trusted contact",
    "emergency.wait": "Waiting period",
    "emergency.request": "Ask for access",
    "emergency.veto": "Stop this",
    "emergency.withdraw": "Withdraw",
    "emergency.status.waiting": "Waiting",
    "emergency.status.open": "Open",
    "emergency.status.vetoed": "Stopped",
    "emergency.status.withdrawn": "Withdrawn",
    "emergency.status.ended": "Ended",

    "security.title": "Extra-private mode",
    "security.explain": "Seal a field with a passphrase we never receive. We store what you can't read without it.",
    "security.enable": "Set a passphrase",
    "security.unlock": "Unlock",
    "security.lock": "Lock",
    "security.locked": "Locked",
    "security.unlocked": "Unlocked for this session",
    "security.passphrase": "Passphrase",
    "security.noRecovery": "There is no recovery. If you forget it, what you sealed is gone.",

    "connect.title": "Connected services",
    "connect.sandbox": "Sandbox",
    "connect.live": "Live",
    "connect.off": "Not set up",
    "connect.toGoLive": "To switch this on",

    "money.rates": "Exchange rates",
    "money.addRate": "Record a rate",
    "money.asOf": "as of",
    "money.notConverted": "Not included in the total",

    "settings.language": "Language",
    "settings.languageHelp": "The app's own words. Figures stay in Indian format in every language.",
  },

  te: {
    "app.name": "అల్మిరా",
    "app.add": "＋ చేర్చండి",
    "app.close": "మూసివేయి",
    "app.save": "భద్రపరచు",
    "app.cancel": "రద్దు",
    "app.remove": "తీసివేయి",
    "app.loading": "ఒక్క క్షణం…",
    "app.somethingWrong": "ఏదో తప్పు జరిగింది.",

    "nav.home": "మొదటి పేజీ",
    "nav.investments": "పెట్టుబడులు",
    "nav.liabilities": "అప్పులు",
    "nav.accounts": "ఖాతాలు",
    "nav.goals": "లక్ష్యాలు",
    "nav.tax": "పన్ను",
    "nav.reports": "నివేదికలు",
    "nav.family": "కుటుంబం",
    "nav.continuity": "నా కుటుంబం కోసం",
    "nav.settings": "సెట్టింగ్‌లు",

    "home.netWorth": "నికర ఆస్తి",
    "home.assets": "ఆస్తులు",
    "home.owed": "అప్పు",
    "home.holdings": "పెట్టుబడులు",
    "home.loans": "రుణాలు",
    "home.scope.me": "నేను",
    "home.empty.title": "ఇంకా ఏమీ నమోదు కాలేదు",
    "home.empty.body": "మీ మొదటి పెట్టుబడిని చేర్చండి — ఎఫ్‌డీ లేదా బంగారం ఇరవై సెకన్లలో అవుతుంది.",
    "home.empty.action": "＋ మొదటిది చేర్చండి",
    "home.attention": "ఒకసారి చూడండి",
    "home.upcoming": "రాబోయేవి",
    "home.whereItSits": "ఎక్కడ ఉంది",
    "home.whoseItIs": "ఎవరిది",

    "continuity.title": "నా కుటుంబం కోసం",
    "continuity.intro": "ఏమి ఉంది, ఎక్కడ ఉంది, ఎవరికి ఫోన్ చేయాలి — మీరు వివరించడానికి లేనప్పుడు.",
    "continuity.included": "చేర్చినవి",
    "continuity.excluded": "ఉద్దేశపూర్వకంగా వదిలినవి",
    "continuity.print": "పుస్తకాన్ని ముద్రించండి",
    "continuity.howToClaim": "మీ కుటుంబం దీన్ని ఎలా పొందాలి",
    "continuity.nominee": "నామినీ",
    "continuity.noNominee": "నామినీ నమోదు కాలేదు",
    "continuity.keptAt": "ఎక్కడ ఉంచారు",
    "continuity.call": "ఫోన్ చేయండి",
    "continuity.steps": "దశలు",
    "continuity.documents": "వారు అడిగేవి",
    "continuity.ready": "ఇది మీ దగ్గర ఉంది",
    "continuity.notReady": "ఇంకా నమోదు కాలేదు",

    "estate.title": "వీలునామా, పత్రాలు",
    "estate.add": "＋ పత్రాన్ని నమోదు చేయండి",
    "estate.location": "అసలు పత్రం ఇక్కడ ఉంది",
    "estate.executor": "అమలుదారు",
    "estate.beneficiaries": "వారసులు",
    "estate.mismatch.title": "నామినీ, వీలునామా వేరుగా ఉన్నాయి",
    "estate.mismatch.explain": "నామినీ డబ్బు అందుకుంటారు; వారసుడు ఆస్తిని పొందుతారు. ఇది ఉద్దేశపూర్వకమేనా చూసుకోండి.",
    "estate.empty": "ఏమీ నమోదు కాలేదు. ఎవరికీ దొరకని వీలునామా లేనట్టే.",

    "contacts.title": "సహాయపడే వ్యక్తులు",
    "contacts.add": "＋ ఒకరిని చేర్చండి",
    "contacts.empty": "సీఏ, ఏజెంట్, వీలునామా ఉంచిన లాయర్ — మీ కుటుంబం ఫోన్ చేయవలసిన వారు.",

    "sharing.title": "పంచుకున్న లింక్‌లు",
    "sharing.create": "＋ ఏదైనా పంచుకోండి",
    "sharing.expires": "గడువు",
    "sharing.views": "తెరిచినవి",
    "sharing.revoke": "వెనక్కి తీసుకో",
    "sharing.copied": "లింక్ కాపీ అయింది.",
    "sharing.empty": "ఏమీ పంచుకోలేదు. ఒక లింక్ ఒక భాగాన్ని మాత్రమే, కొంతకాలం చూపుతుంది.",

    "emergency.title": "మీరు అందుబాటులో లేనప్పుడు",
    "emergency.trusted": "నమ్మకమైన వ్యక్తి",
    "emergency.wait": "వేచి ఉండే కాలం",
    "emergency.request": "అనుమతి అడగండి",
    "emergency.veto": "ఆపండి",
    "emergency.withdraw": "వెనక్కి తీసుకో",
    "emergency.status.waiting": "వేచి ఉంది",
    "emergency.status.open": "తెరిచి ఉంది",
    "emergency.status.vetoed": "ఆపారు",
    "emergency.status.withdrawn": "వెనక్కి తీసుకున్నారు",
    "emergency.status.ended": "ముగిసింది",

    "security.title": "అదనపు గోప్యత",
    "security.explain": "మాకు తెలియని పాస్‌ఫ్రేజ్‌తో ఒక వివరాన్ని మూసివేయండి. అది లేకుండా మేము దాన్ని చదవలేం.",
    "security.enable": "పాస్‌ఫ్రేజ్ పెట్టండి",
    "security.unlock": "తెరవండి",
    "security.lock": "మూసివేయండి",
    "security.locked": "మూసి ఉంది",
    "security.unlocked": "ఈ సెషన్‌కు తెరిచి ఉంది",
    "security.passphrase": "పాస్‌ఫ్రేజ్",
    "security.noRecovery": "తిరిగి పొందే మార్గం లేదు. మరిచిపోతే, మూసిన సమాచారం పోతుంది.",

    "connect.title": "అనుసంధానించిన సేవలు",
    "connect.sandbox": "పరీక్ష",
    "connect.live": "ప్రత్యక్షం",
    "connect.off": "సిద్ధం కాలేదు",
    "connect.toGoLive": "దీన్ని ఆన్ చేయడానికి",

    "money.rates": "మారకపు రేట్లు",
    "money.addRate": "రేటు నమోదు చేయండి",
    "money.asOf": "నాటికి",
    "money.notConverted": "మొత్తంలో చేర్చలేదు",

    "settings.language": "భాష",
    "settings.languageHelp": "యాప్ పదాలు మాత్రమే. అంకెలు అన్ని భాషల్లోనూ భారతీయ పద్ధతిలోనే ఉంటాయి.",
  },

  hi: {
    "app.name": "अल्मीरा",
    "app.add": "＋ जोड़ें",
    "app.close": "बंद करें",
    "app.save": "सहेजें",
    "app.cancel": "रद्द करें",
    "app.remove": "हटाएँ",
    "app.loading": "एक क्षण…",
    "app.somethingWrong": "कुछ गड़बड़ हो गई।",

    "nav.home": "मुख्य",
    "nav.investments": "निवेश",
    "nav.liabilities": "देनदारी",
    "nav.accounts": "खाते",
    "nav.goals": "लक्ष्य",
    "nav.tax": "कर",
    "nav.reports": "रिपोर्ट",
    "nav.family": "परिवार",
    "nav.continuity": "मेरे परिवार के लिए",
    "nav.settings": "सेटिंग्स",

    "home.netWorth": "वास्तविक कुल संपत्ति",
    "home.assets": "संपत्ति",
    "home.owed": "देनदारी",
    "home.holdings": "निवेश",
    "home.loans": "ऋण",
    "home.scope.me": "मैं",
    "home.empty.title": "अभी कुछ दर्ज नहीं है",
    "home.empty.body": "अपना पहला निवेश जोड़ें — एफ़डी या सोना दर्ज करने में बीस सेकंड लगते हैं।",
    "home.empty.action": "＋ पहली चीज़ जोड़ें",
    "home.attention": "ध्यान देने योग्य",
    "home.upcoming": "आने वाला",
    "home.whereItSits": "कहाँ रखा है",
    "home.whoseItIs": "किसका है",

    "continuity.title": "मेरे परिवार के लिए",
    "continuity.intro": "क्या है, कहाँ है, और किसे फ़ोन करना है — जब आप समझाने के लिए मौजूद न हों।",
    "continuity.included": "शामिल",
    "continuity.excluded": "जानबूझकर बाहर रखा",
    "continuity.print": "पुस्तिका छापें",
    "continuity.howToClaim": "आपका परिवार इसे कैसे प्राप्त करे",
    "continuity.nominee": "नामिती",
    "continuity.noNominee": "कोई नामिती दर्ज नहीं",
    "continuity.keptAt": "कहाँ रखा है",
    "continuity.call": "फ़ोन करें",
    "continuity.steps": "चरण",
    "continuity.documents": "वे क्या माँगेंगे",
    "continuity.ready": "यह आपके पास है",
    "continuity.notReady": "अभी दर्ज नहीं",

    "estate.title": "वसीयत और काग़ज़ात",
    "estate.add": "＋ दस्तावेज़ दर्ज करें",
    "estate.location": "मूल प्रति यहाँ है",
    "estate.executor": "निष्पादक",
    "estate.beneficiaries": "उत्तराधिकारी",
    "estate.mismatch.title": "नामिती और वसीयत अलग-अलग हैं",
    "estate.mismatch.explain": "नामिती पैसा प्राप्त करता है; उत्तराधिकारी उसका मालिक बनता है। देख लें कि यह जानबूझकर है।",
    "estate.empty": "कुछ दर्ज नहीं है। जो वसीयत किसी को मिले ही नहीं, वह होने के बराबर नहीं।",

    "contacts.title": "मदद करने वाले लोग",
    "contacts.add": "＋ किसी को जोड़ें",
    "contacts.empty": "सीए, एजेंट, वसीयत रखने वाले वकील — जिन्हें आपके परिवार को फ़ोन करना होगा।",

    "sharing.title": "साझा किए गए लिंक",
    "sharing.create": "＋ कुछ साझा करें",
    "sharing.expires": "समाप्ति",
    "sharing.views": "खोला गया",
    "sharing.revoke": "वापस लें",
    "sharing.copied": "लिंक कॉपी हो गया।",
    "sharing.empty": "कुछ साझा नहीं किया। एक लिंक सिर्फ़ एक हिस्सा, कुछ समय के लिए दिखाता है।",

    "emergency.title": "जब आप उपलब्ध न हों",
    "emergency.trusted": "विश्वसनीय व्यक्ति",
    "emergency.wait": "प्रतीक्षा अवधि",
    "emergency.request": "पहुँच माँगें",
    "emergency.veto": "रोकें",
    "emergency.withdraw": "वापस लें",
    "emergency.status.waiting": "प्रतीक्षा में",
    "emergency.status.open": "खुला",
    "emergency.status.vetoed": "रोका गया",
    "emergency.status.withdrawn": "वापस लिया",
    "emergency.status.ended": "समाप्त",

    "security.title": "अतिरिक्त निजता",
    "security.explain": "एक ऐसे पासफ़्रेज़ से कोई जानकारी सील करें जो हमें कभी नहीं मिलता। उसके बिना हम उसे पढ़ नहीं सकते।",
    "security.enable": "पासफ़्रेज़ सेट करें",
    "security.unlock": "खोलें",
    "security.lock": "बंद करें",
    "security.locked": "बंद है",
    "security.unlocked": "इस सत्र के लिए खुला",
    "security.passphrase": "पासफ़्रेज़",
    "security.noRecovery": "इसे वापस पाने का कोई रास्ता नहीं। भूल गए तो सील की गई जानकारी चली जाएगी।",

    "connect.title": "जुड़ी हुई सेवाएँ",
    "connect.sandbox": "परीक्षण",
    "connect.live": "सक्रिय",
    "connect.off": "सेट नहीं",
    "connect.toGoLive": "इसे चालू करने के लिए",

    "money.rates": "विनिमय दरें",
    "money.addRate": "दर दर्ज करें",
    "money.asOf": "तारीख़",
    "money.notConverted": "कुल में शामिल नहीं",

    "settings.language": "भाषा",
    "settings.languageHelp": "केवल ऐप के शब्द। अंक हर भाषा में भारतीय पद्धति में ही रहते हैं।",
  },
};

let current = readStored();

function readStored() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored && messages[stored]) return stored;
  } catch { /* private mode */ }
  // The browser's preference, when we speak it.
  const preferred = (navigator.languages || [navigator.language || "en"])
    .map((tag) => String(tag).slice(0, 2).toLowerCase())
    .find((code) => messages[code]);
  return preferred || "en";
}

export const language = {
  get code() { return current; },
  set(code) {
    if (!messages[code]) return;
    current = code;
    try { localStorage.setItem(STORAGE_KEY, code); } catch { /* ignore */ }
    document.documentElement.lang = code;
  },
};

/** Falls back to English rather than showing a key: a broken screen is worse than an English one. */
export function t(key, params) {
  const text = messages[current]?.[key] ?? messages.en[key] ?? key;
  if (!params) return text;
  return Object.entries(params).reduce(
    (out, [name, value]) => out.replaceAll(`{${name}}`, String(value)),
    text,
  );
}

/**
 * Dates in the reader's own language; amounts deliberately not. ₹1,76,875 is
 * how the amount is written here regardless of the language around it, and the
 * server sends the canonical string anyway (docs/14).
 */
export function localDate(iso) {
  if (!iso) return "—";
  const locale = { en: "en-IN", te: "te-IN", hi: "hi-IN" }[current] || "en-IN";
  return new Date(iso).toLocaleDateString(locale, {
    day: "numeric", month: "short", year: "numeric",
  });
}
