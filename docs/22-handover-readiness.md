[‹ Index](README.md)

# 22 · Handover readiness

The owner asked for this: *"Turns the estate work already built (transmission
assistant, family handbook, emergency access) into one visible number with the
gaps named: nominee recorded, document uploaded, a contact who knows, location
filled in. Not a dashboard for its own sake — a to-do list for the thing nobody
wants to think about until it's too late."*

The question it answers is simple: **if you could not explain anything tomorrow,
how much of what you would hand over could your family actually use?** It
answers with a number, and then lists every missing item.

This document is the spec, and it records the decisions made when the feature
was built. Status: **built** (backend, web client). No migration. The native app
has no surface for it yet.

---

## 1. The model: four checks, each worth the same

There are four checks. They are the owner's four, and nothing else:

| check | what it asks | applies to |
|---|---|---|
| `nominee` | Is a nominee recorded? | each counted holding of a type that has nominees (§3) |
| `document` | Is a scan attached? | each counted holding of a type where a scan helps, and each will or other instrument |
| `location` | Is there a sealed "where the original is"? | each counted holding of a type with a physical original, and each will or other instrument |
| `trusted_contact` | Have you named someone who can ask for emergency access, and can that person sign in? | you, once |

**The score is the average of the checks that apply. Within each check, it is
the share of items that are done. The result is rounded down.**

```
score = floor( 100 × mean over applicable checks of (done ÷ applicable) )
```

Here is a worked example. You have ten holdings. Five have a nominee (50%). All
ten have a scan (100%). Of the four with a physical original, two have a sealed
location (50%). You have named a trusted contact who signs in (100%). The score
is the average of 50, 100, 50 and 100, which is **75**. Adding one more nominee
adds 10 percentage points to the nominee check, so the score goes up by 2.5.
The score is rounded down, so it becomes **77**.

Why this model and not the others:

- **Not a weighted sum of items.** The existing completeness score
  (`CompletenessService`, on Reports) weights nominee 3, proof 2, and so on,
  across every item. Nobody can predict a weighted score, and a big household
  drowns out a household-level gap. With 30 holdings and three checks each, that
  is 90 items. "Nobody can ask for access" would then be one item in 91, and the
  score would show 98%. That is exactly the gap this feature exists to name. A
  check's weight should not depend on how many records you happen to have.
- **Not a simple fraction of all items.** That model has the same drowning
  problem, for the same reason.
- **Each check is equal.** None of the four is more important than the others
  in a way we could defend. A will nobody can find and a policy with no nominee
  both leave the family stuck. Equal weights are the one choice a person can
  hold in their head.
- **Rounded down, always.** If one gap remains, the score is at most 99. Take
  199 of 200 nominees with everything else complete: that is 99.875%. Rounding
  to the nearest whole number would show **100** with a policy still missing its
  nominee. Completeness rounds that way today (see known-issues 19). Here,
  `complete` is true only when there are no gaps at all. The pure function and
  the API are both tested for this (§8).

### When there is no number

Doc 18 §6 applies: *never show a number the data did not earn.* The score is
`null`, and `scoreExplanation` gives the reason, when:

| situation | sentence |
|---|---|
| nothing is recorded that you can see | "Nothing is recorded for your family yet, so there is nothing to score." |
| everything you can see is left out of the family summary | "Everything you can see is left out of the family summary, so there is nothing to score." |
| records exist, but none of the three record checks applies to any of them (for example, only an IPO application) | "None of the checks applies to what is recorded, so there is no honest score." |

The last row matters. Without it, a household whose only record is an IPO
application, and which has named a trusted contact, would score 100%. A 100%
earned by one household-level item, with nothing handed over, is not a fact.
The gaps are still listed in every one of these cases, so the to-do list works
even when there is no number.

When a single check has nothing it applies to, that check is left out of the
average. It is not counted as done. The check still appears in `checks`, with
`applicable: 0` and `percent: null`.

## 2. Which records count

These are the records the continuity code already treats as the handover, read
the same way `HandbookService` reads them:

| record | counted when |
|---|---|
| `investment` (includes insurance, deposits, gold, property, custom types) | `active` or `matured`, not deleted, not rolled over into a successor, and `is_in_continuity` |
| `estate_document` (will, codicil, POA, trust…) | `executed` and not deleted. The handbook prints these, and emergency access reveals them |

Records **not** counted:

- **Liabilities.** A debt's handover is that the family knows it exists. The
  handbook already does that by listing it, and emergency access reveals it.
  None of the four checks is the right question for a loan: a loan has no
  nominee, the papers sit with the lender, and a scan of a credit card
  statement helps nobody claim anything. Scoring debts would add gaps that
  could not be closed in any way that helps.
- **Accounts.** The handbook does not include accounts, and they have no
  continuity flag. A locker's location is the obvious case to add later. It
  would need `accounts.is_in_continuity` first.
- **Draft, superseded or revoked instruments.** The family should not act on
  them.
- **Records left out of the family summary** (`is_in_continuity = false`). The
  owner decided the family should not be handed these, so missing items on them
  are not handover gaps. They are counted in `leftOutCount` and named in a
  caveat, never listed.

**This is the one way to move the score without fixing anything, and it is
allowed on purpose.** If you leave the records with gaps out of the summary, the
score rises. We did not block this. Leaving a record out is an explicit,
per-record decision the handbook already honours. Scoring it would mean nagging
someone about a choice docs/05 §3.4 says is theirs. Two things keep it honest:
the caveat always states how many records are left out, and leaving *everything*
out produces no score at all (§1).

## 3. Which checks apply to which holding

A check applies only where it could be done and would matter. A nominee cannot
be recorded for gold in the almirah. A "where is the original" line for a demat
holding has no original to point at. Counting these would create gaps nobody can
close, and people would learn to ignore the list.

This table is the whole policy. `HandoverReadinessDocTest` reads it back out of
`HandoverChecks`, so this table and the code cannot disagree. A seeded type that
is missing from the table fails the same test.

<!-- handover-applicability:start -->
| type_code | nominee | document | location |
|---|---|---|---|
| `gold_physical` | no | yes | yes |
| `gold_jewelry` | no | yes | yes |
| `gold_digital` | no | yes | no |
| `gold_sgb` | yes | yes | no |
| `silver` | no | yes | yes |
| `fd` | yes | yes | no |
| `rd` | yes | yes | no |
| `tax_saver_fd` | yes | yes | no |
| `mf_sip` | yes | yes | no |
| `mf_lumpsum` | yes | yes | no |
| `stock_listed` | yes | yes | no |
| `stock_unlisted` | no | yes | yes |
| `esop_rsu` | no | yes | no |
| `ipo_application` | no | no | no |
| `bond` | yes | yes | no |
| `ppf` | yes | yes | yes |
| `epf` | yes | yes | no |
| `nps` | yes | yes | no |
| `ssy` | no | yes | yes |
| `nsc_kvp` | yes | yes | yes |
| `scss` | yes | yes | no |
| `insurance_term` | yes | yes | yes |
| `insurance_endowment` | yes | yes | yes |
| `insurance_ulip` | yes | yes | yes |
| `insurance_health` | no | yes | no |
| `property` | no | yes | yes |
| `reit` | yes | yes | no |
| `chit_fund` | no | yes | no |
| `crypto` | no | no | yes |
| `business_equity` | no | yes | yes |
| `collectible` | no | yes | yes |
| `p2p_lending` | no | yes | no |
| `savings_buffer` | yes | no | no |
| `cash_on_hand` | no | no | yes |
| `loan_given` | no | yes | yes |
| `universal` | no | yes | yes |
<!-- handover-applicability:end -->

A household's own custom type takes its category's row:

<!-- handover-category-defaults:start -->
| category_code | nominee | document | location |
|---|---|---|---|
| `gold` | no | yes | yes |
| `deposits` | yes | yes | no |
| `mutual_funds` | yes | yes | no |
| `equity` | yes | yes | no |
| `ipo` | no | no | no |
| `bonds` | yes | yes | no |
| `retirement` | yes | yes | no |
| `insurance` | yes | yes | yes |
| `real_estate` | no | yes | yes |
| `alternatives` | no | yes | yes |
| `cash` | no | no | yes |
| `universal` | no | yes | yes |
<!-- handover-category-defaults:end -->

A type whose category is not in this table either gets `document` and
`location` but not `nominee`, the same as "Anything Else".

The reasoning behind the rows that are not obvious:

- **`location` means an original whose loss blocks or slows a claim.** Examples
  are a policy document (LIC asks for the original), a PPF or SSY passbook, an
  NSC certificate, a deed, physical share certificates, and gold. An FD receipt
  is not on that list, because the bank pays on the account number and the
  playbook asks for "receipt *or* account number". Demat, folio and PF holdings
  have no original at all.
- **Crypto: `location` yes, `document` no.** Crypto's "original" is the recovery
  phrase, and where it is kept is the single thing an heir needs. A *scan* of it
  would be the worst file this product could hold, so the document check does
  not apply and nothing asks for one.
- **Cash on hand: `location` only.** There is nothing to scan and nobody to
  nominate. The question is where the cash is.
- **Health insurance: no nominee.** It pays for treatment, not on death. Whether
  a policy has a nominee field is not the question. The question is whether that
  nominee would receive anything a family is waiting for.
- **Savings buffer: nominee only.** It is a bank balance. The bank pays the
  nominee, and neither a scan nor an original changes that.
- **IPO application: nothing.** It is not yet a holding, and it becomes one or
  is refunded within days.

Estate instruments take `document` and `location`, and not `nominee`. A will
has executors and beneficiaries, not nominees.

## 4. How each check is decided

Everything is read on the runtime connection, under the viewer's own row-level
security. Nothing is read from the owner pool.

- **`nominee`:** an `investment_nominees` row exists for the holding. The
  nominee does not need a login: a nominee is a name. *Recorded here* is not
  *registered with the institution*, and a caveat says so.
- **`document`:** a `document_links` row links the holding to a document that
  is not deleted and that the viewer can read. For an instrument, the check
  looks at `estate_documents.document_id` and `document_links` with
  `entity_type = 'estate'`, under the same conditions. A deleted scan does not
  count: the family cannot open a file that is not there.
- **`location`:** a `sealed_values` row exists for the record with
  `field_key = 'original_location'` (docs/20). **This checks presence only.**
  The server never opens the value, because it cannot, and presence is what
  docs/20 §6 set aside for this. A value sealed by a co-owner counts, because
  the location is recorded.
  - **An unsealed note does not count.** If the record has no sealed location
    but does have `investments.storage_location` or `estate_documents.location`
    filled in, the gap is `location_unsealed`: "Where it is is in an unsealed
    note. Seal it on the record." That note is the sentence docs/20 calls the
    most damaging in the database. If it counted, the score would reward
    exactly what docs/20 is retiring, and the fix is one tap on the same
    record's card.
  - **What a sealed location does not prove.** The family can read it only with
    the passphrase (docs/20 §1.2). The server cannot know whether they have
    it. When any sealed location is counted, a caveat says so, in the same
    words the where-and-who screen uses.
  - The key holder (`key_holder`) is not a check. It matters for a locker, and
    lockers are accounts, which are not counted (§2).
- **`trusted_contact`:** you, as a member of this household, have named at
  least one emergency contact who can actually ask. That person's member row
  has a login, is not deleted, and holds an active membership in the household.
  Naming someone, even a parent, who has no login does not count: nobody could
  make the request, and a check that passes while nobody can act is the gaming
  case. The gap explains which situation you are in:
  - `no_trusted_contact`: someone could be named, and nobody is.
  - `trusted_contact_cannot_ask`: everyone you named has no login, or has
    left.
  - `nobody_to_name`: nobody else in the household can sign in. The fix is to
    invite someone first.

  If the viewer is not a person in the household (no member row), the check
  does not apply.

### Why "a contact who knows" is the emergency contact, and not a contact card per record

The alternative was a per-record check: is a contact card (the agent, the CA,
the banker) linked to each holding? That was rejected:

- For most holdings, nobody is the contact. An FD has a branch, and gold has
  nobody. A per-record contact check would be a gap that is closed by inventing
  a contact card, which is the gaming the owner warned against.
- The transmission assistant already names who to call for each holding type
  (`contactHint`: "the servicing branch printed on the policy"). That covers the
  per-record question without a record.
- The person who *knows* in the sense that matters is the person who can open
  the records when you cannot. That is the emergency contact (V20). Without
  one, nothing marked for the family ever reaches the family, however complete
  every record is. That one fact is why this check counts as a quarter of the
  score and not one item in ninety.

## 5. Per viewer

**Two members of the same household can see different scores, and both are
right.** Every read runs as the viewer, so:

- Another member's private holding is not counted, not listed as a gap, and not
  part of `leftOutCount`. You cannot learn from your score that a private record
  exists or what is missing on it.
- A sealed location, nominee or scan counts only if it is visible to you. In
  practice these follow the record's own visibility (V4, V12, V22).
- **Only ordinary sight counts.** A trusted contact inside an open emergency
  window can see the subject's continuity records (V20). Those records are not
  counted in the trusted contact's own score. Readiness is about what *you*
  would hand over, not about what was handed to you. The query requires
  `app.can_read_record(…)` with the ownership predicate, which leaves out the
  emergency branch of the read policy.
- **Guest links.** A link is opened through `/share/{token}`, which never calls
  this endpoint, so a guest has no route to it. The service still refuses a
  request that carries a guest identity (`403`) in case a future route passes one
  through: a CA looking at a tax pack has no handover to prepare.

A gap on a household-visible record owned by someone else is listed. The
typical case is a parent's LIC policy, typed in by the adult child: that child
is exactly the person who can find the policy document. Some fixes on such a
record are limited by who may write it. Adding a nominee, for instance, needs
the owner. The gap still names the record, so the viewer knows who to ask.

## 6. "Still true?" does not affect readiness

This was considered and rejected. The two features answer different questions:

- **Readiness** asks: can the family find it, and claim it?
- **Still true?** (docs/21) asks: is the figure or the status still right?

A record that has not been confirmed in a year still has its nominee, its scan
and its sealed location. Lowering the readiness score because of that would:

1. **Make the number fall when nobody did anything.** A score that drops on a
   calendar date cannot be predicted from what you did, and §1 exists to
   prevent that.
2. **Ask the same question twice.** The Still true? card already asks. A second
   nag about the same record, on a screen about something else, teaches people
   to dismiss both.
3. **Mix up a fix with a reminder.** A readiness gap is closed by adding
   something. A confirmation is closed by looking, and it comes back.

The link between the two is real, but it runs the other way. When "Still true?"
finds a closed or matured record, marking it closed takes it out of readiness,
because only `active` and `matured` records count.

## 7. API and web client

`GET /api/v1/households/{householdId}/continuity/readiness`

`score` is left out of the JSON when it is null, like every null field in this
API (`default-property-inclusion: non_null`).

```
{ score: 75 | null,
  scoreExplanation: "…",               // always a sentence
  complete: false,                      // true only when score is not null and there are no gaps
  recordCount: 12,                      // counted records (§2)
  leftOutCount: 1,
  checks: [ { code, label, done, applicable, percent | null } ],   // always all four, in order
  gaps:   [ { check, reason, recordType | null, recordId | null, title | null, fix } ],
  caveats: [ "…" ] }
```

- `gaps` lists every gap, with no cap. The household's own gap comes first,
  followed by record gaps grouped by record in title order, and within a record
  in check order. That makes the list read as a to-do list, one record at a
  time.
- `reason` is one of `no_nominee`, `no_document`, `no_location`,
  `location_unsealed`, `no_trusted_contact`, `trusted_contact_cannot_ask`,
  `nobody_to_name`. Clients translate by `reason` and fall back to `fix`, which
  is English (Doc 14).
- `recordType` / `recordId` are the deep link. `investment` opens the holding,
  where the nominee card, the documents and the where-and-who card all are.
  `estate_document` opens its where-and-who card. A household gap has no record
  and points at the emergency-access card.

**Web client.** A **"Ready to hand over"** card sits at the top of *For my family*
(`#/continuity`). It shows the score, or the null sentence, the four checks as
`done of applicable`, and the gaps as a to-do list grouped by record. Each row
opens the place where the item is fixed, and the card reloads after the fix. The
caveats sit under a disclosure. Strings are in English, Telugu and Hindi
(`ready.*`). If the endpoint fails (for example, on an older server), the card is
simply not drawn, and the screen does not fail with it.

## 8. What is verified, and what is not

**Watched failing** (the protection was removed, the test failed for the stated
reason, and passed again once it was restored):

- Rounding to the nearest instead of rounding down: `ReadinessScoreTest` puts
  199 of 200 in one check with the others complete, and gets 100.
- A named trusted contact with no login counted as done: the API test for "a
  score cannot reach 100 with a gap present" gets 100 and `complete: true`.
- An unsealed note counted as a location: the same kind of test reaches 100
  with only an unsealed note.
- The readiness queries run on the owner pool (no row-level security), with
  the ordinary-sight predicate also removed: the spouse's view counts the
  owner's private holdings (`recordCount` 2, `leftOutCount` 1) and names them.
- The ordinary-sight predicate (`app.can_read_record`) removed: a trusted
  contact with an open emergency window has the subject's policy counted in
  their own score (`recordCount` 1, not 0). With that predicate removed and
  row-level security still on, the privacy test passes, which is why the
  owner-pool watch above removed both. Row-level security and the predicate
  each hide another member's private records on their own.
- The "no record check applies" null removed: a household with only an IPO
  application and a trusted contact shows 100 and `complete: true`.
- The "everything left out" branch removed: the score is still null, because
  the "no record check applies" rule also catches it, and only the sentence is
  wrong. With both removed, the score is 100 with `recordCount` 0.
- The deleted-document condition removed: a holding whose only scan was deleted
  still counts as having one.
- The applicability table and this document disagreeing: `HandoverReadinessDocTest`
  fails.

**Tested but not watched failing:**

- The check order and the gap order.
- `nobody_to_name`, `no_trusted_contact` and `trusted_contact_cannot_ask` (the
  last one is watched failing above, through the login rule).
- The document and location checks on estate instruments.
- Every seeded type and category having a row (`HandoverReadinessDocTest`).
  This test failed for real the first time it ran: `p2p_lending` had been left
  out of the table.

**Not verified:**

- The native app. It has no readiness surface and does not read the endpoint.
- The web card has no automated test. It was checked by hand in a browser
  against a development server, using a seeded household: a term policy with
  nothing, gold with an unsealed note, an FD with a nominee and a scan, a
  private will, and a trusted contact with no login. The card showed 18%, and
  the arithmetic checks: (50 + 25 + 0 + 0) ÷ 4. The eight gaps were grouped by
  record. The "You" row opened the trusted-contact sheet, and naming the spouse
  redrew the card at 43%. The policy's row opened the holding. Adding a nominee
  there and closing the sheet redrew the card at 56%. The will's row opened its
  where-and-who sheet. The card rendered in Telugu at phone width and in Hindi.
  All three null sentences, and the English fallback for an unknown `reason`,
  were rendered by calling the module from the page. A failed load returned no
  card. Two small changes came after that session and were not seen in a browser:
  the "0 of 3" count no longer wraps in Telugu, and each to-do row now has an
  accessible name.
- Cost at scale. The card makes one query per record kind, with correlated
  `exists` subqueries per record. That is fine at family size. It has not been
  measured at thousands of records.

**Known overlap:** the completeness card on Reports (`CompletenessService`)
predates this and scores something related in a different way: it is weighted,
it rounds to the nearest, it shows 100 when nothing is recorded, and it counts
records outside continuity. Both are left as they are, because changing
completeness changes a v1 response. See known-issues 19.

[‹ Index](README.md)
