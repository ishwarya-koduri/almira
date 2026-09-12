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
    envelope = read("app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/zk/Envelope.kt")
    aad = read("app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/zk/Aad.kt")
    service = read("backend/src/main/kotlin/tech/bhrigu/almira/e2e/SealedFieldService.kt")
    reference = read("backend/src/test/kotlin/tech/bhrigu/almira/e2e/E2eApiTest.kt")
    web = read("backend/src/main/resources/static/app/e2e.js")
    android_kdf = read("app/shared/src/androidMain/kotlin/tech/bhrigu/almira/shared/zk/PassphraseKey.android.kt")
    ios_kdf = read("app/shared/src/iosMain/kotlin/tech/bhrigu/almira/shared/zk/PassphraseKey.ios.kt")
    ios_aead = read("app/shared/src/iosMain/kotlin/tech/bhrigu/almira/shared/zk/Aead.ios.kt")
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

    types = set(re.findall(r'"(investment|liability|account|member|estate_document)"', service))
    want("the recordType vocabulary is the five the doc lists",
         types == {"investment", "liability", "account", "member", "estate_document"},
         f"code has {sorted(types)}")
    for literal, label in (
        ("MIN_ITERATIONS = 100_000", "the iteration floor is 100 000"),
        ("MAX_CIPHERTEXT = 64_000", "the ciphertext ceiling is 64 000 characters"),
        ("fieldKey.length > 64", "fieldKey is capped at 64"),
        ('minBytes = 17', "ciphertext must decode to at least 17 bytes"),
    ):
        want(label, literal in service)
    want("the doc states 600 000 iterations and the code agrees",
         "600 000" in doc and "ITERATIONS = 600_000" in web)

    want("the conformance vector's derived key is asserted in the shared tests",
         "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f"
         in read("app/shared/src/commonTest/kotlin/tech/bhrigu/almira/shared/zk/InteropKatTest.kt"))
    want("the conformance vector's envelope is asserted too",
         "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ"
         in read("app/shared/src/androidUnitTest/kotlin/tech/bhrigu/almira/shared/zk/InteropKatEnvelopeTest.kt"))
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
         in read("app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/zk/PassphraseKey.kt"))
    want("a sealed value is never normalised",
         "fun bytesOf(text: String): ByteArray = text.encodeToByteArray()"
         in read("app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/zk/SealedValue.kt"))

    # §7 says AES-GCM on iOS cannot come from Kotlin and is injected from Swift.
    want("iOS AES-GCM is declared as an injected seam, not called from Kotlin",
         "interface AppleAead" in ios_aead and "fun installAppleAead" in ios_aead)
    want("and something in Swift actually implements it",
         "AES.GCM.seal" in swift_aead and "AppleAead" in swift_aead)
    want("the doc says AES-GCM on iOS is injected from Swift",
         "injected from" in doc and "CryptoKit is Swift-only" in doc)

    print()
    print(f"{CHECKS} checks")
    if FAILURES:
        print(f"\n{len(FAILURES)} disagreement(s) between docs/12 and the code:")
        for failure in FAILURES:
            print(f"  · {failure}")
        sys.exit(1)
    print("docs/12 and the code agree.")


if __name__ == "__main__":
    main()
