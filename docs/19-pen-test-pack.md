[‹ Index](README.md)

# 19 · Pen-test pack

> **Status.** This document is being assembled for Phase 1 item 5. The section
> below is written and stands on its own; the threat model, trust boundaries,
> RLS enforcement points, KMS envelope scheme, zero-knowledge fields, OTP and
> auth flow, and the "what I would attack first" section follow.

---

## How the verification is verified

Put first because it is load-bearing for everything after it. Every claim in
this pack rests on a check, and a check that cannot fail is not evidence — it is
a comfortable noise. This project has produced two of those, in one week, and
both were in the checking layer rather than in the product.

**The rule: no check is trusted until it has been watched failing.**

Not "until it passes". Until someone has broken the thing it guards, seen the
check go red, read the message it printed, and put the thing back. A check
written and observed only in its passing state has been observed in the one
state that carries no information.

### The two that got through, and what they teach

**A probe that reported a failing test as passing.** A script read JUnit XML and
picked the failure node with

```python
node = tc.find('failure') or tc.find('error')
```

An `ElementTree` element is **falsy when it has no child elements**. A
`<failure message="…">stack trace</failure>` has text and no children, so it is
falsy, so `or` moved past it and returned `None`, so the script printed that the
test had passed. The build had already said `BUILD FAILED` two lines above; the
summary contradicted it and the summary was the thing being read.

*The lesson is not "know that Python gotcha".* It is that the tool which
**summarises** results is itself untested code, and it fails in the direction
that looks like success. A summariser must be handed a known failure and made to
report it before anyone reads its output.

**A query that checked the wrong rows.** A test asserted that an interrupted
write left a key untouched, and read `select … from e2e_keys` — every
household's key, in a suite that shares a database. It blew up on `single()`,
which was luck: had two rows been identical it would have passed while checking
nothing in particular.

*The lesson:* a check scoped more widely than the thing it is checking may be
right by accident. Scope narrowly enough that the assertion can only be about
the case at hand.

### What is therefore required of a new check

1. **Break it on purpose, once.** Change the code or the data so the check must
   fail, run it, and keep the failure output. If it does not fail, it is not
   checking what its name says.
2. **Read the failure message as a stranger would.** It should name the thing
   that is wrong and where to look. `AadCaseTest` says *"AAD case mismatch"* in
   every message for exactly this reason: the symptom is an authentication-tag
   failure, which reads like a broken cipher, and the message is what stops the
   next person spending a day on the wrong layer.
3. **Restore, and confirm green.** Byte-for-byte — `git diff` empty, not "looks
   the same".
4. **Scope the assertion to the case.** If it reads more than the thing under
   test, it can pass for a reason unrelated to the thing under test.
5. **Distrust summaries hardest.** Anything that aggregates, counts, greps or
   parses results is code between you and the truth. Cross-check it against the
   raw signal — the exit code, the runner's own output — at least once.

### Where this has been done, and where it has not

| Check | Watched failing? | How |
|---|---|---|
| `scripts/check-spec.py` | yes | found a real contradiction on its first run — the reference implementation using an API the spec forbids by name |
| `AadCaseTest` | yes | lowercasing removed from `Aad.of`; message and case-diff read; restored |
| rotation-interrupted-before-commit | yes | `setRollbackOnly()` removed; failed with *"a salt from a rotation that never committed must not survive"* |
| the icon safe-zone measurement | yes | caught a shipped maskable icon at 0.451 against a 0.400 limit |
| the Gradle wrapper fix | yes | jar moved aside in a throwaway clone; `./gradlew` failed; restored |
| the restore sweep | **not yet** | planned: corrupt a ciphertext in a restored copy, confirm the exact row is named, restore it ([Doc 17 §6](17-deploying.md)) |

**For a tester reading this:** the honest summary is that the checks have been
adversarially tested more carefully than the product has. Treat the table above
as the list of claims with evidence behind them, and everything else in this
pack as a claim with an argument behind it. The difference matters, and
[Doc 18 §4](18-handover.md) is the standing list of what has never been verified
at all.

[‹ Index](README.md)
