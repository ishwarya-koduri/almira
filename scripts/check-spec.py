#!/usr/bin/env python3
"""Checks docs/12 against the code it describes, and fails if they disagree.

    python3 scripts/check-spec.py

docs/12 is the contract a second client implements from. Three of its claims
had quietly become false as the real clients were written — it told a Kotlin
implementer to use an API its own §2 forbids by name, told an Apple implementer
to call a framework that cannot be reached from Kotlin, and omitted that the
uuids in the AAD are lowercased. None of that was caught by anything, because
prose is not executed.

So this executes it. Two kinds of assertion:

  CONSTANTS      every number and literal the document states is read back out
                 of the code that implements it.
  PROHIBITIONS   the things §2 and §7 tell an implementer *not* to do are
                 checked for absence in the paths that derive or seal. A spec
                 that says "not this API" and a codebase that uses it is the
                 exact drift this file exists to stop.

Run by `scripts/dev.sh test` and by `dev-personal/move/verify.sh`, so it fails
where someone is looking.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILURES: list[str] = []
CHECKS = 0


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.exists():
        FAILURES.append(f"{relative} does not exist — the spec points at it")
        return ""
    return path.read_text()


def code_only(source: str) -> str:
    """Source with comments removed, so a doc comment saying "deliberately not
    X" is never mistaken for a use of X. That distinction is the whole reason
    the prohibitions below can be checked at all."""
    without_block = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return "\n".join(
        line for line in without_block.splitlines()
        if not line.lstrip().startswith(("//", "*"))
    )


def want(label: str, condition: bool, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if condition:
        print(f"  ok   {label}")
    else:
        print(f"  FAIL {label}")
        FAILURES.append(f"{label}{(' — ' + detail) if detail else ''}")


def main() -> None:
    doc = read("docs/12-end-to-end-encryption.md")
    envelope = read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/Envelope.kt")
    aad = read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/Aad.kt")
    service = read("backend/src/main/kotlin/tech/almira/e2e/SealedFieldService.kt")
    reference = read("backend/src/test/kotlin/tech/almira/e2e/E2eApiTest.kt")
    web = read("backend/src/main/resources/static/app/e2e.js")
    android_kdf = read("app/shared/src/androidMain/kotlin/tech/almira/shared/zk/PassphraseKey.android.kt")
    ios_kdf = read("app/shared/src/iosMain/kotlin/tech/almira/shared/zk/PassphraseKey.ios.kt")
    ios_aead = read("app/shared/src/iosMain/kotlin/tech/almira/shared/zk/Aead.ios.kt")
    swift_aead = read("app/iosApp/iosApp/CryptoKitAead.swift")

    print("CONSTANTS — what the document states, read back out of the code")

    want("envelope header is 1 + 4 + 12 bytes",
         "IV_BYTES: Int = 12" in envelope and "HEADER_BYTES: Int = 1 + 4 + IV_BYTES" in envelope)
    want("GCM tag is 16 bytes", "TAG_BYTES: Int = 16" in envelope)
    want("the smallest envelope is header + tag, and the doc says 33",
         "MINIMUM_BYTES: Int = HEADER_BYTES + TAG_BYTES" in envelope
         and "smallest legal envelope is 33 bytes" in doc)
    want("format version is 1", "VERSION: Int = 1" in envelope)
    want("an unknown version refuses rather than guessing",
         "throw UnsupportedEnvelopeVersion(version)" in envelope)
    want("a non-positive keyVersion is malformed", "keyVersion <= 0" in envelope)
    want("base64url is written without padding",
         "PaddingOption.ABSENT" in envelope and "base64url without padding" in doc)

    want("the AAD lowercases the two uuids and nothing else",
         "householdId.lowercase(), recordType, recordId.lowercase(), fieldKey" in aad)
    want("the doc states the lowercasing rule",
         "lowercased, on seal and on open" in doc)
    want("the AAD separator is U+007C and refused inside a component",
         "SEPARATOR: Char = '|'" in aad and "offender" in aad)

    vocabulary = {"investment", "liability", "account", "member", "estate_document", "document"}
    types = set(re.findall(r'"(investment|liability|account|member|estate_document|document)"', service))
    want("the recordType vocabulary is the six the doc lists",
         types == vocabulary
         and "`investment`, `liability`, `account`, `member`, `estate_document`, `document`" in doc,
         f"code has {sorted(types)}")
    migration = read("db/migrations/V28__where_and_who.sql")
    want("the table's recordType check agrees with the service",
         "'investment','liability','account','member','estate_document','document'" in migration)
    for literal, label in (
        ("MIN_ITERATIONS = 100_000", "the iteration floor is 100 000"),
        ("MAX_CIPHERTEXT = 64_000", "the ciphertext ceiling is 64 000 characters"),
        ("fieldKey.length > 64", "fieldKey is capped at 64"),
        ("MIN_ENVELOPE_BYTES = 33", "ciphertext must decode to at least 33 bytes, the smallest envelope"),
        ("ENVELOPE_VERSION = 1", "ciphertext must carry version byte 1"),
    ):
        want(label, literal in service)
    want("the doc states the 33-byte server floor",
         "at least **33 bytes** decoded" in doc)
    want("the table's floor is 33 bytes as 44 base64 characters",
         "length(ciphertext) >= 44" in migration)

    # docs/20: the two field keys are a contract between the clients, and the
    # server hands them out, so all three must spell them the same way.
    doc20 = read("docs/20-where-and-who.md")
    where_service = read("backend/src/main/kotlin/tech/almira/e2e/WhereAndWho.kt")
    where_web = read("backend/src/main/resources/static/app/where.js")
    for key in ("original_location", "key_holder"):
        want(f"the field key `{key}` is the same in docs/20, the server and the web client",
             f"`{key}`" in doc20 and f'"{key}"' in where_service and f'"{key}"' in where_web)
    want("docs/20 says search is client-side and the web client does not send the query anywhere",
         "no server-side search" in doc20.lower() and "api.search" not in code_only(where_web))

    # docs/20 §1: no plaintext fallback. The capture form and the new-will form
    # must not ask; the rest of "nothing can write it" is check_plaintext_location_retired.
    capture_web = code_only(read("backend/src/main/resources/static/app/screens/capture.js"))
    column_order = re.search(r"columnOrder\s*=\s*\[(.*?)\]", capture_web, flags=re.S)
    want("the capture form does not ask for storage_location in plain text",
         column_order is not None and "storage_location" not in column_order.group(1)
         and "storageLocation" not in capture_web)
    continuity_web = code_only(read("backend/src/main/resources/static/app/screens/continuity.js"))
    create_estate = re.search(r"api\.createEstateDocument\((.*?)\}\);", continuity_web, flags=re.S)
    want("the new-estate-document form does not post location in plain text",
         create_estate is not None and not re.search(r"\blocation\s*:", create_estate.group(1)))

    # docs/api/README.md: a delayed code still opens the code step, a refused
    # channel switches to the one the server named, and the three ways of not
    # sending each have their own sentence in every language. The decision is
    # asserted in scripts/check-auth-outcome.js; here, that the screen asks it on
    # every path that can meet those answers, and that the sentences exist.
    auth_web = code_only(read("backend/src/main/resources/static/app/screens/auth.js"))
    want("the sign-in screen asks auth-outcome.js on request, resend and verify",
         auth_web.count("signInOutcome(error, channel)") == 3
         and "export function signInOutcome" in read("backend/src/main/resources/static/app/auth-outcome.js"))
    i18n_web = read("backend/src/main/resources/static/app/i18n.js")
    for stem in ("auth.code.delayed", "auth.error.deliveryFailed", "auth.error.providerUnavailable",
                 "auth.error.serviceUnavailable", "auth.switched"):
        for channel in ("phone", "email"):
            key = f'"{stem}.{channel}":'
            want(f"{stem}.{channel} is written in English, Telugu and Hindi", i18n_web.count(key) == 3,
                 f"found {i18n_web.count(key)}")

    want("the doc states 600 000 iterations and the code agrees",
         "600 000" in doc and "ITERATIONS = 600_000" in web)

    want("the conformance vector's derived key is asserted in the shared tests",
         "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f"
         in read("app/shared/src/commonTest/kotlin/tech/almira/shared/zk/InteropKatTest.kt"))
    want("the conformance vector's envelope is asserted too",
         "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ"
         in read("app/shared/src/androidUnitTest/kotlin/tech/almira/shared/zk/InteropKatEnvelopeTest.kt"))
    for token in ("17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f",
                  "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ"):
        want(f"the doc carries the constant {token[:16]}…", token in doc)

    print()
    print("PROHIBITIONS — what §2 and §7 tell an implementer not to do")

    # The one that decides whether two clients agree must not be delegated to a
    # platform provider. Checked in the derivation paths *and* in the reference
    # implementation the document points at as code to copy — which is the one
    # that had it.
    for relative, source in (
        ("app/shared/.../PassphraseKey.android.kt", android_kdf),
        ("app/shared/.../PassphraseKey.ios.kt", ios_kdf),
        ("backend/.../E2eApiTest.kt  (the reference §7 points at)", reference),
    ):
        body = code_only(source)
        want(f"no SecretKeyFactory or PBEKeySpec in {relative}",
             "SecretKeyFactory" not in body and "PBEKeySpec" not in body,
             "§2 forbids it: the char-to-byte step belongs to the provider and "
             "differs between Android and the JVM")

    want("no CCKeyDerivationPBKDF on iOS",
         "CCKeyDerivationPBKDF" not in code_only(ios_kdf),
         "its password parameter maps to a Kotlin String?, which hands text-to-bytes "
         "to the interop layer and cannot carry a NUL")

    want("Android derives over HMAC-SHA256 directly",
         'Mac.getInstance' in code_only(android_kdf) and "HmacSHA256" in android_kdf)
    want("iOS derives over CCHmac directly",
         "CCHmacInit" in code_only(ios_kdf) and "CCHmacFinal" in code_only(ios_kdf))
    want("the passphrase is NFC-normalised before UTF-8, in one place",
         "normalizeNfc(passphrase).encodeToByteArray()"
         in read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/PassphraseKey.kt"))
    want("a sealed value is never normalised",
         "fun bytesOf(text: String): ByteArray = text.encodeToByteArray()"
         in read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/SealedValue.kt"))

    # §7 says AES-GCM on iOS cannot come from Kotlin and is injected from Swift.
    want("iOS AES-GCM is declared as an injected seam, not called from Kotlin",
         "interface AppleAead" in ios_aead and "fun installAppleAead" in ios_aead)
    want("and something in Swift actually implements it",
         "AES.GCM.seal" in swift_aead and "AppleAead" in swift_aead)
    want("the doc says AES-GCM on iOS is injected from Swift",
         "injected from" in doc and "CryptoKit is Swift-only" in doc)

    check_recovery(doc, web)
    check_plaintext_location_retired()
    check_privacy_notice()
    check_scores_are_earned()

    print()
    print(f"{CHECKS} checks")
    if FAILURES:
        print(f"\n{len(FAILURES)} disagreement(s) between docs/12 and the code:")
        for failure in FAILURES:
            print(f"  · {failure}")
        sys.exit(1)
    print("docs/12 and the code agree.")


# ---------------------------------------------------------------------------
# docs/12 §10: recovery. The code format, the HKDF info and the key id are a
# contract between clients as much as §2–§4 are, and the server enforces the
# consistency rules the document states.
# ---------------------------------------------------------------------------

def check_recovery(doc: str, web: str) -> None:
    print()
    print("RECOVERY — docs/12 §10 read back out of both clients and the server")
    codes = read("backend/src/main/resources/static/app/recovery-codes.js")
    reference = read("backend/src/test/kotlin/tech/almira/e2e/RecoveryReference.kt")
    reference_test = read("backend/src/test/kotlin/tech/almira/e2e/RecoveryReferenceTest.kt")
    check_js = read("scripts/check-recovery.js")
    service = read("backend/src/main/kotlin/tech/almira/e2e/SealedFieldService.kt")
    recovery = read("backend/src/main/kotlin/tech/almira/e2e/Recovery.kt")
    migration = read("db/migrations/V55__recovery_for_sealed_fields.sql")

    alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    want("the code alphabet is Crockford's, in the doc and both clients",
         f"`{alphabet}`" in doc and f'"{alphabet}"' in codes and f'"{alphabet}"' in reference)
    want("the secret is 21 bytes in both clients",
         "SECRET_BYTES = 21" in codes and "SECRET_BYTES = 21" in reference and "**21 random bytes**" in doc)
    want("the HKDF info is the same string in the doc, the web client and the reference",
         "almira recovery v1\\|{kind}" in doc and "`almira recovery v1|${kind}`" in web
         and '"almira recovery v1|$kind"' in reference)
    want("the content key id is the same HMAC label everywhere",
         "`almira content key id v1`" in doc and '"almira content key id v1"' in web
         and '"almira content key id v1"' in reference)
    want("shares are combined with the AES polynomial, reduced by 0x1b, in both clients",
         "x ^= 0x1b" in codes and "x = x xor 0x1B" in reference)
    fixed = (
        ("the sheet code", "04000-0820C-20A1G-7104G-M2RC1-M70Y4-0H289-H8JCE", (doc, reference_test, check_js, web)),
        ("the sheet's wrapping key", "94b5b1c89dd4196ad9b6de8cdd669f446013310874b9eb4d0bcea169811061ce", (doc, reference_test, web)),
        ("the content key id", "VrqqDb7VVhHIkN5-ZUdCvQ", (doc, reference_test, web)),
    )
    for label, constant, sources in fixed:
        want(f"{label} is the same fixed answer in the doc and every implementation that asserts it",
             all(constant in source for source in sources))
    want("a passphrase write while copies exist is refused unless it names the same key",
         "recovery_copies_would_break" in service and "recovery_copies_would_break" in doc)
    want("a copy of another key is refused",
         "recovery_key_mismatch" in recovery and "recovery_key_mismatch" in doc)
    want("making, replacing and removing a copy need a step-up",
         code_only(recovery).count("requireStepUp(userId)") == 2 and "step-up" in doc)
    want("the table allows only HKDF-SHA256 and AES-GCM-256",
         "check (kdf = 'HKDF-SHA256')" in migration and "check (wrap_algorithm = 'AES-GCM-256')" in migration)
    want("no Math.random anywhere near a secret",
         "Math.random" not in code_only(codes) and "Math.random" not in code_only(web))


# ---------------------------------------------------------------------------
# docs/20 §1 and V33: the plaintext location is retired. No SQL, Kotlin or JS
# may read or write it again — not the three columns, not a type or attribute
# that asks for it, not a server copy into a duplicate or a template, not a
# handbook or guide that prints it, not a client that sends it.
# ---------------------------------------------------------------------------

RETIRED_ALLOWED_KOTLIN = {
    # The v1 schema keeps the request and response fields (additive-only). A
    # request field exists to be refused; a response field is always absent.
    "val storageLocation: String? = null,",
    "val location: String? = null,",
    "val whereItIsKept: String? = null,",
    'RetiredPlaintextLocation.refuseIfSent("storageLocation", body.storageLocation)',
    'RetiredPlaintextLocation.refuseIfSent("storageLocation", input.storageLocation)',
    'RetiredPlaintextLocation.refuseIfSent("location", input.location)',
}


# The attribute keys a plaintext "where" sentence lived under besides the column's
# own name: "Where the agreement is" and "Where the keys are" (V6, retired in V34).
RETIRED_WHERE_KEYS = ("agreement_location", "wallet_hint")
RETIRED_KEYS_LINE = 'val ATTRIBUTE_KEYS: Set<String> = setOf("storage_location", "agreement_location", "wallet_hint")'


def sql_only(source: str) -> str:
    return "\n".join(line.split("--", 1)[0] for line in source.splitlines())


def migration_version(path: Path) -> int:
    match = re.match(r"V(\d+)__", path.name)
    return int(match.group(1)) if match else 0


def check_plaintext_location_retired() -> None:
    print()
    print("RETIRED — the plaintext location (docs/20 §1, V33)")

    migration = sql_only(read("db/migrations/V33__retire_plaintext_locations.sql"))
    want("V33 refuses, and changes nothing, while any plaintext location is non-empty",
         "raise exception" in migration
         and "length(storage_location) > 0" in migration
         and "length(location) > 0" in migration
         and "row_security_active" in migration
         and migration.index("raise exception") < migration.index("drop column"))
    want("V33 drops the three columns and takes the field out of every type",
         all(f"alter table {table} drop column {column};" in re.sub(r"\s+", " ", migration)
             for table, column in (("investments", "storage_location"),
                                   ("investment_templates", "storage_location"),
                                   ("estate_documents", "location")))
         and "#- '{common,storage_location}'" in migration)

    v34 = sql_only(read("db/migrations/V34__retire_plaintext_where_fields.sql"))
    want("V34 refuses, and changes nothing, while agreement_location or wallet_hint holds text",
         "raise exception" in v34 and "row_security_active" in v34
         and all(f"attributes ->> '{key}'" in v34 for key in RETIRED_WHERE_KEYS)
         and v34.index("raise exception") < v34.index("update investments"))
    want("V34 takes both fields out of every type and adds the constraints",
         "type_does_not_ask_for_plaintext_where" in v34
         and "no_plaintext_where_in_attributes" in v34
         and "no_plaintext_where_in_template_attributes" in v34
         and "custom_field_is_not_plaintext_where" in v34)
    retired_kotlin = code_only(read("backend/src/main/kotlin/tech/almira/e2e/RetiredPlaintextLocation.kt"))
    want("the service refuses all three retired keys by name, on holdings and templates",
         RETIRED_KEYS_LINE in retired_kotlin
         and code_only(read("backend/src/main/kotlin/tech/almira/investment/InvestmentService.kt"))
             .count("RetiredPlaintextLocation.withoutRetiredAttribute(input.attributes)") == 2
         and code_only(read("backend/src/main/kotlin/tech/almira/template/TemplateService.kt"))
             .count("RetiredPlaintextLocation.withoutRetiredAttribute(input.attributes)") == 2
         and "field.key in RetiredPlaintextLocation.ATTRIBUTE_KEYS"
             in code_only(read("backend/src/main/kotlin/tech/almira/catalog/CatalogService.kt")))

    offenders: list[str] = []

    # Later migrations: nothing may bring a column or a schema entry back.
    for path in sorted((ROOT / "db/migrations").glob("V*.sql")):
        if migration_version(path) <= 33:
            continue
        body = sql_only(path.read_text())
        if migration_version(path) > 34 and any(key in body for key in RETIRED_WHERE_KEYS):
            offenders.append(f"db/migrations/{path.name}")
        if "storage_location" in body or re.search(r"\badd\s+column\s+(if\s+not\s+exists\s+)?location\b", body, re.I):
            offenders.append(f"db/migrations/{path.name}")

    # The server.
    estate_like = ("/estate/", "HandbookService.kt", "TransmissionService.kt")
    for path in sorted((ROOT / "backend/src/main/kotlin").rglob("*.kt")):
        relative = str(path.relative_to(ROOT))
        for number, line in enumerate(code_only(path.read_text()).splitlines(), 1):
            stripped = line.strip()
            if stripped in RETIRED_ALLOWED_KOTLIN:
                continue
            if path.name == "RetiredPlaintextLocation.kt" and stripped == RETIRED_KEYS_LINE:
                continue
            if (any(key in stripped for key in RETIRED_WHERE_KEYS)
                    or "storage_location" in stripped or "storageLocation" in stripped or "whereItIsKept" in stripped
                    or re.search(r"(?<![\w.])[a-z]{1,3}\.location\b", stripped)
                    or (any(part in relative for part in estate_like) and re.search(r"\blocation\b", stripped))):
                offenders.append(f"{relative}: {stripped}")

    # The web client and the native app.
    for root in ("backend/src/main/resources/static", "app/shared/src/commonMain"):
        for path in sorted((ROOT / root).rglob("*")):
            if path.suffix not in (".js", ".kt"):
                continue
            relative = str(path.relative_to(ROOT))
            for line in code_only(path.read_text()).splitlines():
                stripped = line.strip()
                # Quoted text is a message key or a label ("where.location"), not a
                # field; template literals are kept, because they print values.
                unquoted = re.sub(r'"(?:[^"\\]|\\.)*"', '""', stripped)
                if ("storage_location" in stripped or "storageLocation" in stripped
                        or "whereItIsKept" in stripped
                        or any(key in stripped for key in RETIRED_WHERE_KEYS)
                        or (path.suffix == ".js" and re.search(r"\.location\b(?!\.)|\blocation\s*:", unquoted)
                            and "self.location" not in unquoted)):
                    offenders.append(f"{relative}: {stripped}")

    # Scripts that post fixtures through the API.
    for path in sorted((ROOT / "scripts").glob("*.sh")):
        body = path.read_text()
        if ("storageLocation" in body or any(key in body for key in RETIRED_WHERE_KEYS)
                or "storage_location" in body) or re.search(r'\\"location\\"\s*:', body):
            offenders.append(f"scripts/{path.name}")

    want("no SQL, Kotlin, JS or fixture reads or writes a plaintext location",
         not offenders, "; ".join(offenders[:8]))

    web_where = code_only(read("backend/src/main/resources/static/app/where.js"))
    # The key-holder line is drawn by holderStep, which is handed its help key
    # and renders it with t(helpKey) — so either spelling is the guidance shown.
    key_holder_help = ('t("where.keyHolderHelp")' in web_where
                       or (re.search(r'holderStep\(\s*"keyHolder"\s*,[^)]*"where\.keyHolderHelp"', web_where)
                           and "t(helpKey)" in web_where))
    want("the web editor gives both sealed lines their guidance",
         bool(key_holder_help) and 't("where.locationHelp")' in web_where)
    # The owner's decision: guide people toward "Amma" or "the CA", not full
    # names. A suggestion list fed from members or contacts puts full names one
    # tap away from that guidance (docs/20 §4).
    want("the key-holder suggestions are roles, never member or contact names",
         't("where.keyHolderSuggestions")' in web_where
         and not re.search(r"\bapi\.contacts\b|\bstate\.members\b|\.displayName\b|\bcontacts\b", web_where))


def check_privacy_notice() -> None:
    print()
    print("PRIVACY NOTICE — the key holder paragraph (docs/23)")
    notice_doc = read("docs/23-privacy-notice.md")
    i18n_web = read("backend/src/main/resources/static/app/i18n.js")
    for key in ("where.keyHolderHelp", "where.locationHelp", "where.keyHolderSuggestions", "privacy.keyHolder.body",
                "privacy.keyHolder.sealed", "privacy.keyHolder.guidance", "privacy.keyHolder.pending",
                "privacy.status"):
        want(f"{key} is written in English, Telugu and Hindi", i18n_web.count(f'"{key}":') == 3,
             f"found {i18n_web.count(chr(34) + key + chr(34) + ':')}")
    english = i18n_web[:i18n_web.index("  te: {")]
    for word in ("“Amma”", "“the CA”", "end-to-end encrypted"):
        want(f"the English key-holder guidance says {word}", word.lower() in english.lower())
    want("docs/23 says legal review of a key holder's consent is pending, without a conclusion",
         "legal review" in notice_doc and "pending" in notice_doc and "“Amma”" in notice_doc)
    native_wording = read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/WhereAndWhoWording.kt")
    want("the native app shows the same key-holder guidance under the sealed field",
         "WhereAndWhoWording.helpFor(state.newFieldKey)"
         in code_only(read("app/shared/src/commonMain/kotlin/tech/almira/shared/zk/ZkScreen.kt"))
         and all(word in native_wording for word in ("“Amma”", "“the CA”", "End-to-end encrypted")))
    want("the notice is reachable from Settings and from onboarding",
         "privacyLink()" in code_only(read("backend/src/main/resources/static/app/screens/settings.js"))
         and "privacyLink()" in code_only(read("backend/src/main/resources/static/app/screens/onboarding.js")))


# ---------------------------------------------------------------------------
# docs/18 §6, known-issues 19, docs/22 §1: never show a number the data did not
# earn. A brand-new household's completeness is `score: 0, scoreEarned: false`,
# and its readiness has no `score` at all. A client that reads `score` without
# reading `scoreEarned` (or the null) tells a new user "0%". So in either client,
# a completeness or readiness score may be read in exactly one place each, and
# that place must read `scoreEarned` / test for the null first.
# ---------------------------------------------------------------------------

# A read of a field called `score`: property access, index by name, or a
# destructuring that names it.
SCORE_READ_JS = re.compile(
    r"\.score\b(?![\w\"'`])|\[\s*[\"'`]score[\"'`]\s*\]|(?:const|let|var|\()\s*\{[^}]*\bscore\b[^}]*\}")
SCORE_READ_KT = re.compile(r"\.score\b|[\"]score[\"]|\bscore\s*=|\$\{?score\b")

WEB_APP = "backend/src/main/resources/static/app"
WEB_SCORE_READERS = {f"{WEB_APP}/completeness.js", f"{WEB_APP}/readiness.js"}
NATIVE_MODELS = "app/shared/src/commonMain/kotlin/tech/almira/shared/api/Models.kt"


def native_client_sources() -> list[Path]:
    """Everything that ships in the app: shared, androidApp and iosApp, not tests."""
    found = []
    for base in ("app/shared/src", "app/androidApp/src", "app/iosApp"):
        for path in sorted((ROOT / base).rglob("*")):
            if path.suffix not in (".kt", ".swift") or "/build/" in str(path):
                continue
            relative = str(path.relative_to(ROOT))
            if "Test" in relative.split("/src/")[-1].split("/")[0] or "UITests" in relative:
                continue
            found.append(path)
    return found


def check_scores_are_earned() -> None:
    print()
    print("SCORES — no client shows a completeness or readiness number the data did not earn")

    # --- web ---------------------------------------------------------------
    readers = []
    for path in sorted((ROOT / WEB_APP).rglob("*.js")):
        relative = str(path.relative_to(ROOT))
        if SCORE_READ_JS.search(code_only(path.read_text())):
            readers.append(relative)
    stray = [r for r in readers if r not in WEB_SCORE_READERS]
    want("web: only completeness.js and readiness.js read a score",
         not stray, "read `score` through completenessPercent or readinessCard instead: " + ", ".join(stray))

    want("web: the score-read pattern still sees the two places that are allowed to read it",
         set(readers) == WEB_SCORE_READERS, f"saw {readers}")

    completeness = code_only(read(f"{WEB_APP}/completeness.js"))
    want("web: completenessPercent gives a number only when scoreEarned is true",
         "report.scoreEarned !== true" in completeness
         and completeness.index("report.scoreEarned !== true") < completeness.index("${report.score}%"))

    readiness = code_only(read(f"{WEB_APP}/readiness.js"))
    tested = 'const hasScore = typeof readiness.score === "number";'
    shown = "${readiness.score}"
    with_score = "hasScore\n    ?"
    without_score = ': el("p", { "data-no-score": "true" }'
    reads = [m.start() for m in re.finditer(r"readiness\.score\b", readiness)]
    # The card draws the number twice (the ring's label and its centre); every
    # read but the test itself sits in the branch that runs when there is one.
    want("web: the readiness card shows a number only when score is not null",
         tested in readiness and shown in readiness and with_score in readiness and without_score in readiness
         and len(reads) >= 2
         and readiness.index(tested) < readiness.index(with_score) < readiness.index(shown)
         and all(readiness.index(with_score) < at < readiness.index(without_score)
                 for at in reads if at != readiness.index(tested) + len("const hasScore = typeof ")))

    for path in sorted((ROOT / WEB_APP).rglob("*.js")):
        relative = str(path.relative_to(ROOT))
        source = code_only(path.read_text())
        if "api.completeness(" in source:
            want(f"web: {relative} shows completeness through completenessPercent",
                 "completenessPercent(" in source)
        if "api.readiness(" in source:
            want(f"web: {relative} fetching readiness is readiness.js, which draws the card",
                 relative == f"{WEB_APP}/readiness.js")

    # --- native ------------------------------------------------------------
    stray = []
    for path in native_client_sources():
        relative = str(path.relative_to(ROOT))
        if relative == NATIVE_MODELS:
            continue
        if SCORE_READ_KT.search(code_only(path.read_text())):
            stray.append(relative)
    want("native: nothing outside Models.kt reads a score; screens use display()",
         not stray, "use Completeness.display() / HandoverReadiness.display(): " + ", ".join(stray))

    models = code_only(read(NATIVE_MODELS))
    want("native: the score-read pattern still sees Models.kt reading it",
         SCORE_READ_KT.search(models) is not None and len(native_client_sources()) > 20)

    def body(name: str) -> str:
        start = models.find(f"data class {name}(")
        if start < 0:
            return ""
        end = models.find("\n}\n", start)
        return models[start:end if end > 0 else len(models)]

    completeness_kt = body("Completeness")
    want("native: Completeness carries scoreEarned (required) and scoreExplanation",
         "\n    val scoreEarned: Boolean,\n" in completeness_kt
         and "val scoreExplanation: String? = null," in completeness_kt)
    want("native: Completeness.score is private, so a screen cannot show it bare",
         "\n    private val score: Int,\n" in completeness_kt
         and completeness_kt.count("$score%") == 1)
    want("native: Completeness.display() gives a number only when scoreEarned",
         re.search(r"if \(scoreEarned\) \{\s*ScoreDisplay\.Percent\(\"\$score%\"\)", completeness_kt) is not None)

    readiness_kt = body("HandoverReadiness")
    want("native: HandoverReadiness.score is private and nullable",
         "\n    private val score: Int? = null,\n" in readiness_kt
         and "val scoreExplanation: String," in readiness_kt)
    want("native: HandoverReadiness.display() gives a number only when score is not null",
         'score?.let { ScoreDisplay.Percent("$it%") } ?: ScoreDisplay.NoScore(scoreExplanation)' in readiness_kt
         and readiness_kt.count("score") - readiness_kt.count("scoreExplanation") == 2)


if __name__ == "__main__":
    main()
