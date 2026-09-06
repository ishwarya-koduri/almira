[‹ Index](README.md)

# 15 · Almira security whitepaper

*Version 1.0 · September 2026 · covers the backend and web client as built through Phase 4*

Almira is a private registry of what a family owns and owes. It never moves
money, never holds funds, and never asks for a banking password. What it does
hold is a complete picture of a household's finances — which is, in some ways, a
more sensitive thing than a bank account, because it is all of them at once.

This document says how that is protected, and where the limits are. It is
written to be read by someone deciding whether to trust us with that picture,
and by an auditor deciding whether the claims are true. Every claim below points
at the code or the test that makes it so.

---

## 1 · The one idea

Most applications conflate two questions: what may you **do**, and what may you
**see**. Almira separates them.

| | |
|---|---|
| **Role** | what you may do — invite people, edit shared records, administer the household |
| **Visibility** | what you may see — decided per record, never by role |

**No role can read another member's private records.** Not an editor, not an
admin, not the person who created the household. Joining a household is not
surrendering financial privacy.

This is not a policy we intend to honour. It is a predicate in the database, and
the application connects as a role that cannot escape it.

## 2 · Where it is enforced

Three layers, so a bug in one cannot leak data.

**PostgreSQL row-level security is the authority.** Every table carrying
household data has policies; every read passes the same predicate. Derived
views (`investment_value`, `goal_funding`, `liability_holder_value`, the
nominee/will mismatch) are declared `security_invoker`, so a view is not a way
around a policy.

**The application runs as a non-owner role.** PostgreSQL lets a table's owner
bypass its own RLS. `almira` owns the schema and runs migrations; `almira_app`
serves every request, owns nothing, and cannot opt out. `GET /health` reports
which role is connected, so a regression is visible at a glance.

**Identity is transaction-scoped.** The authenticated user is set with
`set_config('app.user_id', …, is_local => true)`, which PostgreSQL discards at
commit or rollback. A connection returned to the pool — or leaked, or killed
mid-request — cannot carry an identity to the next borrower. A statement outside
a transaction has no identity and returns nothing: it fails closed.

**A record you may not see returns 404, not 403.** A 403 would confirm that it
exists, which is the leak the model is designed to prevent.

## 3 · What that buys, concretely

- Two members of one household see different net worth figures, and both are
  correct. A private holding contributes **nothing** — not even its amount — to
  another member's total.
- Everything derived inherits the same filter: returns, tax packs, goal funding,
  the completeness score, search, exports, the printed family handbook. There is
  no separate "and remember to filter this" step, because the rows were never
  returned.
- A joint holding cannot be hidden from a co-owner. Ownership is a read
  predicate, not a courtesy.
- Only a record's **holder** may share it. An admin who can see a household
  record cannot grant anyone else access to it.

## 4 · Encryption

**In transit**: TLS; HSTS with a two-year max-age and subdomains included.

**At rest, server-side**: account numbers, policy numbers and document contents
are encrypted with a per-household data key, itself wrapped by a key-encryption
key held outside the database. A dump of PostgreSQL yields ciphertext and a
wrapped key that nothing in the dump can open. Every ciphertext is bound by
additional authenticated data to *where it lives* — household, table, column —
so a value cannot be moved to another row and decrypted there.

By default only the **last four digits** of a number are stored at all. The rest
is kept only on explicit opt-in, and seeing it again requires a fresh
re-authentication on that session, recorded in the audit log.

**At rest, zero-knowledge**: any field a person chooses can be sealed with a
passphrase the server never receives. AES-256-GCM under a key derived by
PBKDF2-HMAC-SHA-256 at 600,000 iterations, bound by AAD to the household, record
and field. The server stores an opaque string and has no means to open it; the
tests prove it by going looking for the plaintext in the database. The scheme is
specified completely in [Doc 12](12-end-to-end-encryption.md) so that a second
client implements the same one.

**No key material is in this repository.** Each development install generates
its own key into a gitignored file, and a test fails the build if anything
key-shaped appears in the source tree. Outside development the process refuses
to start without a key supplied deliberately.

## 5 · Authentication and sessions

Phone plus one-time code; no passwords to leak, phish or reuse. Access tokens
last fifteen minutes. **Refresh tokens are single-use**: presenting one that has
already been rotated is treated as theft, and the entire session is revoked.
Sensitive actions — revealing a full account number, opening a document —
require a **recent re-authentication on that session**, so a borrowed unlocked
phone is not enough.

Rate limits apply per phone number and per IP, and the one-time code namespaces
sign-in separately from step-up so one cannot be used for the other.

## 6 · The three ways someone outside the family sees something

Each is a **narrowing** of the model above, never a bypass.

**A guest link** — a tax pack to a CA for a week. The scope is materialised when
the link is made, by reading under the sharer's own visibility, so a link can
never contain something the sharer could not see and cannot widen later. Opening
it runs in a guest session where every read policy *additionally* requires the
row to be named by that link, and where the transaction is read-only — enforced
by the database, not by the caller remembering. The token is 256 random bits,
stored only as a hash. An expired link and a withdrawn one are deliberately
indistinguishable from outside.

**Emergency access** — a person the owner has named asks, and then nothing
happens for a waiting period the owner chose. Both sides are notified; the owner
can stop it at any point; the whole sequence is audited. The window also opens
only if the owner has genuinely not used Almira since the request: signing in is
the plainest possible statement that somebody is reachable, and it stops the
clock without them having to understand what a veto is. When it does open it
reveals records marked for continuity and the will, and nothing else — and it
grants reading only. The state is derived from timestamps and activity, so no
failed background job can leave an unlock switched on.

**An advisor** — a colleague with a login who sees only what has been explicitly
shared with them. Household visibility does not reach them, and they cannot
write.

## 7 · Auditing

An append-only activity log records writes, sensitive views, exports, sign-ins,
share creation and every access-grant transition. The application role is
granted INSERT and nothing else on it: it cannot update or delete its own audit
trail.

Outbound notifications are recorded with channel, template, title and outcome —
never a body, because a notification carries amounts and institution names and a
message log is the least protected thing we write.

## 8 · Data rights

Export in machine-readable form (CSV, XLSX) and as a printable PDF, always
scoped to what the person asking may see. Deletion is soft first and purgeable
after. A departing member's own records export cleanly; private records leave
with their owner.

Alignment with India's DPDP Act 2023 is by design rather than by retrofit:
purpose limitation, consent recorded when storing another adult's data, a
guardian basis for minors, and — for the eventual import of bank data — the
**Account Aggregator** framework, which exists precisely so that nobody has to
hand over a net-banking password. Almira will never scrape, and never asks for
one.

## 9 · What this does not defend against

A security document that oversells itself is worse than none.

- **A compromised device or browser.** Malware, or a malicious extension, sees
  what the person sees — including a zero-knowledge passphrase as it is typed.
- **A malicious server, for browser-delivered end-to-end encryption.** The web
  client is served by the same server that stores the ciphertext; a server that
  wanted the plaintext could ship a build that exfiltrates it. This is the
  fundamental limit of the approach and applies to every product in the
  category. The honest mitigations are a native client with a signed binary and,
  eventually, reproducible builds.
- **Metadata on sealed fields.** That a field is sealed, when it was written,
  roughly how long it is, and which record it belongs to are all visible to the
  server.
- **A determined insider with production database access**, for anything not
  sealed. Envelope encryption defends the dump, not the live system; the answer
  there is operational — least privilege, key custody outside the database, and
  audit — and it is a smaller claim than "we cannot read your data", which is
  true only of sealed fields.
- **Availability.** Backups and disaster recovery are operational commitments,
  not properties of this codebase.

## 10 · How the claims are checked

Every statement above corresponds to a test that runs on every build.

| | |
|---|---|
| SQL, run as the application role, independent of any application code | **59 assertions** |
| Unit and full-stack HTTP tests | **375 tests** |
| End-to-end over real HTTP against a running server | **151 checks** across three suites |

The privacy suites deliberately include the *derived* surfaces — totals, returns,
tax, goal funding, completeness, exports, the guest clamp and the emergency
window — because derived data leaks as readily as the records it comes from.

The controls and the threat model, in the form an auditor asks for, are in
[Doc 16](16-controls-and-threat-model.md).

## 11 · Still to do

Stated here rather than omitted:

- **An external penetration test.** Not yet scheduled. It should cover the
  authentication and step-up flows, the guest-link surface, the emergency-access
  state machine, and the multi-tenant boundary — and it should be done against a
  deployed environment, not a laptop.
- **A third-party review of the zero-knowledge scheme** before it is described
  to users as anything stronger than "the server cannot read this".
- **Reproducible builds** for the native clients, once they exist.
- **Formal DPDP and SOC 2 readiness work**, which is an operational programme
  rather than a code change.

[‹ Index](README.md)
