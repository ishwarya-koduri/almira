[‹ Index](README.md)

# 20 · Where it is, and who holds the key

The owner asked for this: *"Every record gains location of original and person
with access; searchable across everything."*

What people will write in it:

- "Original in the steel almirah, second shelf"
- "Locker at SBI Ameerpet, key with Amma"
- "Share certificates with the CA"

The owner also named the risk: this is **the single most damaging sentence in
the database if it leaks**. It tells a burglar or a hostile relative where the
papers and keys are. And it names a third party, the key holder, who never
agreed to be written down.

This document is the spec, and it records the decisions made when the feature
was built. Status: **built** (migration V28, backend, web client). The older
plaintext columns are **retired** (migration V33, §1). Native app: **no editor**;
it shows the key-holder guidance on its generic sealed-field screen. See §9.

---

## 1. Decision (a): zero-knowledge, and nothing unsealed to fall back to

**Both fields are sealed values under the [Doc 12](12-end-to-end-encryption.md)
scheme.** The server has no plaintext column for either. It has no "unsealed if
you have no passphrase" mode, and it never keeps a copy of the text anywhere.

Why:

- It is the obvious candidate. Doc 12 §1 already gave "a locker address, the
  name of the person holding a key" as its examples of what gets sealed.
- A plaintext fallback would decide the question for most people. Setting a
  passphrase takes effort. If a plain field sat next to the sealed one, most
  locations would end up in the plain field, and the database would hold exactly
  the sentence the owner is worried about.
- Nothing in the code makes it unworkable. `sealed_values` (V22) is already
  generic: any record type from a closed vocabulary, any field key. The
  per-record RLS predicate already follows the record. The web client, Android
  and iOS already seal and open values that interoperate. Rotation already leaves
  field ciphertext untouched.

**Two reasons in the code that push against this, and what was done about each:**

1. **Older plaintext columns held this sentence.** `investments.storage_location`
   ("Kept at", V3), `investment_templates.storage_location` (V16) and
   `estate_documents.location` ("The original is", V18) were plain text, and
   eight seeded types asked for `storage_location` in their field schema. The
   server read them in search, in the continuity handbook and its PDF, and in the
   transmission guide, and copied `storage_location` into duplicates and between
   holdings and templates. The server cannot encrypt them itself, because it does
   not have the key.
   *What was done first:* the web client stopped asking, and offered a
   **Seal it, and clear the unsealed note** button on any record that still had
   a note, only when the person could still read a location afterwards.
   *What was done since, on the owner's decision* ("Right now 'no plain-text
   fallback' isn't true — the server can still create one. Do it before real users
   exist"): **V33 retires all of it.**

   - **The migration refuses rather than destroys.** If any of the three columns,
     or any `attributes.storage_location`, holds a non-empty value, in the trash
     or not, V33 raises an error that says how many of each kind and changes
     nothing. The server does not start until they are moved. It also refuses if
     row-level security is active for the migrating role, because a count taken
     under RLS could be zero while other people's notes exist.
   - **Then it drops the columns**, strips the empty key from attributes, takes
     `storage_location` out of every type's `field_schema` (`common` and
     `fields`), deletes any custom field definition by that name, and adds check
     constraints so a type, a custom field or an attributes object cannot carry
     it again. *Dropped, not renamed and revoked:* after the refusal there is
     nothing left to keep; a column-level `REVOKE` does not bind the table owner,
     which is the role migrations, sweeps and restores use; and `select i.*`
     would fail for the app role on a revoked column, so every read would have to
     change anyway.
   - **No server path reads or writes one.** Capture, edit, duplicate, rollover,
     template save, template edit and template apply, will create and edit,
     search, the handbook (JSON and PDF), the transmission guide and handover
     readiness no longer mention the columns. The v1 schema keeps the request
     fields and the response fields, because v1 is additive-only; response fields
     are always absent now.
   - **A request that still sends text is refused, not ignored:**
     `400 plaintext_location_retired`, with `details.field` naming the field
     (`storageLocation`, `location`, `attributes.storage_location` on a template,
     or `key` for a custom field called `storage_location`). The message never
     echoes the text. Ignoring it would answer 200 to a client that believes it
     has recorded where the will is. Null and blank carry no location, so they are
     accepted and do nothing, which keeps an older client that sends `""` working.
   - **The clients.** The web client no longer shows or sends either field; the
     warning and the move button are gone with the data they moved, and every
     capture form ends with a line pointing to the sealed card. The native app
     no longer renders `storage_location` even if an older server's schema names
     it, and never sends it. `scripts/check-spec.py` fails if any Kotlin, SQL
     (in V34 onwards), JS or fixture script reads or writes a plaintext location.

   **A database that still holds notes.** V33's message names the counts. To
   move them, run a build from **before** V33 (for example commit `02a198d`)
   against that database. In its web client, unlock, open each holding's detail
   screen, or "Where the original is" on a will under *For my family*, and press
   **Seal it, and clear the unsealed note**. Restore a trashed holding from
   Settings → Trash first. Then start this build again. The rows are listed by:

   ```sql
   select 'investment', id, household_id, title, deleted_at is not null as trashed
     from investments where length(storage_location) > 0
   union all
   select 'estate_document', id, household_id, title, deleted_at is not null
     from estate_documents where length(location) > 0
   union all
   select 'template', id, household_id, name, deleted_at is not null
     from investment_templates where length(storage_location) > 0;
   ```

   A note the button does not offer to move (a slot sealed by someone else, or
   one that will not open), a template, a deleted will, or an
   `attributes.storage_location` value has no button. Someone has to decide
   about each on purpose: retype it into the sealed card and then set the column
   to `''`, or clear it by hand. The migration never makes that decision.
2. **Continuity.** The people who most need "where is the will" are the family,
   after a death or incapacity. Emergency access (V20) gives a trusted contact
   more *rows*. It cannot give them the passphrase. They will see that a location
   is recorded, and they will not be able to read it. This is the real price of
   the decision, and the UI says so plainly: *"Your family can't read these after
   you're gone unless they have the passphrase. Decide now how they will get it."*
   The handbook PDF is built on the server, so it cannot include these fields
   either. A client-side print after unlocking would solve that. It is not built.

## 2. Decision (b): which records "every record" means

These are the record kinds in this codebase that have a **physical original, or a
key, somewhere**. Each one takes both fields:

| `recordType` | table | covers | why |
|---|---|---|---|
| `investment` | `investments` | every holding: FDs, gold, property, shares, bonds, **insurance policies** (an investment type), **custom templates** and "anything else" | a certificate, a bond, a deed, the gold itself |
| `liability` | `liabilities` | loans, credit cards, family loans | sanction letters, mortgage papers held by the lender |
| `account` | `accounts` | savings, demat, folio, wallet, **locker** | the locker is the canonical case: which branch, and who has the key |
| `document` | `documents` | the vault's scanned files | the scan's paper original (**new**: V28 adds it to the sealed-value vocabulary) |
| `estate_document` | `estate_documents` | wills, codicils, POAs, trusts | a will nobody can find is a will that does not exist |

**Not covered, on purpose:**

- `member` and `contact` are people, not papers. `member` stays in the sealed
  vocabulary for other sealed fields.
- `goal` is an intention, and nothing about it is physical.
- Transactions, valuations, tax lots and nominees are parts of a holding. The
  holding carries the location.

**How the fields attach generically, not per table.** No table gets a new column.
A value is a row in `sealed_values`, addressed by
`(householdId, recordType, recordId, fieldKey)`. The two field keys are fixed:

| field | `fieldKey` |
|---|---|
| where the original is | `original_location` |
| who holds the key or the papers | `key_holder` |

Writes go through the existing endpoints:
`PUT /e2e/values/{recordType}/{recordId}/{fieldKey}` and `DELETE` on the same
path. One new read gathers every covered record with its two slots:

```
GET /api/v1/households/{id}/where-and-who[?recordType=&recordId=]
→ { fieldKeys: { originalLocation, keyHolder },
    records: [ { recordType, recordId, title,
                 originalLocation?: { ciphertext, keyVersion, updatedAt, sealedByMe },
                 keyHolder?:        { ciphertext, keyVersion, updatedAt, sealedByMe } } ],
    caveats: [...] }
```

The server hands out `fieldKeys`, so a client never has to guess the spelling.
`scripts/check-spec.py` checks that the spelling is the same in this document, in
`WhereAndWho.kt` and in the web client.

A new record kind joins by doing three things: add it to the `sealed_values`
check constraint and to `app.linked_record_visible` (one migration), add it to
`SealedFieldService.RECORD_TYPES`, and add one `union all` branch to the index
query. No new endpoint and no new column.

## 3. Decision (c): search, and the compromise stated plainly

**Search across sealed fields does force a compromise.** The server cannot read
these values, so the server cannot search them. The only honest design is to
search on the device:

1. The client fetches the index. It contains ciphertext and titles.
2. After unlocking, the client opens every value it can, in memory.
3. The client matches the query against the title, the location and the key
   holder. The match is a substring match after NFKC and a locale lower-case. The
   stored value is never normalised (Doc 12 §3). Folding is only for comparison.

What that costs, without softening:

- **No server-side search.** The global search bar (`GET /search`) never finds a
  location. It used to match the old plaintext `storage_location`; that column is
  gone (§1).
- **No search while locked.** The locked screen shows only presence (§6) and an
  unlock form.
- **Search cost grows with record count.** Every value is decrypted on each visit
  to the screen. AES-GCM over a few hundred short strings takes milliseconds. At
  thousands of records it would need paging or a worker. That has not been
  measured; see §9.
- **No fuzzy search, no stemming, no search index.** A search index of the words
  would be a second copy of the words. If it were stored, it would be a plaintext
  leak. If it were kept in memory, it would do nothing a substring match does not.
  "Ameerpet" finds "SBI Ameerpet". "Amerpet" does not.
- **Only what you can open.** A value sealed by another member shows as "Sealed
  by someone else", and its words are not searched.
- **The query never leaves the page.** It is not sent in a request, not put in the
  URL, and not stored in `localStorage`. The input has autocomplete off.

**A non-secret hint is refused.** One option was to let people leave a label
unsealed, such as "SBI" or "home", so the server could search it. That was
rejected. A hint is a partial location, and partial locations are what the owner
wants kept out of the database. The one plaintext thing a record already has is
its **title**, and search matches titles on the device too. Titles are not sealed
(Doc 12 §1), so the editor does not encourage putting the location in them.

## 4. Decision (d): the key holder is free text, not a link

**It is stored as a sealed free-text string**, such as "Amma" or "Ramesh (CA)". It
is not a foreign key to a `members` or `contacts` row.

Why a link was rejected:

- **A plaintext link is itself the leak.** A `key_holder_contact_id` column, or a
  `contact_links` row with `role = 'key_holder'`, tells the server, a database
  dump and any member who can see that contact that *this person holds the key to
  something*. Half of the damaging sentence would be in plaintext.
- **A sealed link does not help.** A contact's uuid sealed inside the value would
  still point at a contact row whose name and phone number are plaintext. It would
  also break silently when that contact is deleted, and search would need a second
  join on the device for no gain.
- **Minimisation.** Recording who holds a key needs a name, as the person would
  say it. It does not need a phone number, an address or an account. The editor
  offers names from members and contacts as **suggestions**. Choosing one copies
  the text and creates no link.
- **The words ask for less than a name.** The owner's decision: *"Design
  mitigation while you get [a legal answer] — the field is end-to-end encrypted
  so we cannot read it, and the UI should guide people toward 'Amma' or 'the CA'
  rather than full names and addresses."* The helper text under the key-holder
  line says to write a role or a relationship ("Amma", "the CA", "my brother"),
  not a full name, an address or a phone number; that it names another person,
  so to say only what the family needs; and that it is end-to-end encrypted, so
  Almira cannot read it. The placeholder is "Amma, the CA, my brother". The
  location line has its own: enough for the family to find it, no street address
  or locker number. Both are in English, Telugu and Hindi (`where.keyHolderHelp`,
  `where.locationHelp`) and, in English, on the native sealed-field screen
  (`WhereAndWhoWording`). The member and contact suggestions stay, because a
  household often calls a member by a relationship already; picking one is still
  the person's choice.

**Consent and the DPDP Act.** The key holder is a data principal who has not
consented. What this design does about that:

- They are never contacted. No message, invite or notification goes to them.
- The name is sealed, so neither the operator nor a breach of the server can read
  or list who is named.
- It is the smallest identifier that works: a name, chosen by the user.
- Removing it is one action. Blank the line and the row is deleted.

Whether a family recording "the key is with Amma" falls within the Act's
personal or domestic purpose exemption is a question for counsel. **Legal review
is pending, and nothing here or in the product states a conclusion.** The design
keeps the question small either way. The privacy notice ([Doc 23](23-privacy-notice.md))
says what is stored, that it names another person, that it is sealed, the
guidance above, and that the consent question is with lawyers.

## 5. Decision (e): visibility

These fields follow the record's own visibility, and they are also sealed.

- **Reading.** `sealed_values_read` (V22) requires
  `app.linked_record_visible(record_type, record_id)`. That function is
  invoker-rights, so its `exists` check runs under the caller's RLS on the
  record's own table. V28 adds the `document` branch. If you cannot see a record,
  you cannot see its sealed rows, and a private record is not in the index at
  all. Tested with another member of the same household, for all five record
  kinds.
- **Writing.** `sealed_values_write` requires the caller to be the one who sealed
  the value and to be able to see the record. On a private record, another member
  is refused as though the record did not exist.
- **Shared records.** A member who can see the record sees that a location
  exists (`sealedByMe: false`) and nothing more. Their content key is their own,
  so the value will not open for them. An attempt to overwrite it answers
  `409 sealed_by_someone_else` with a sentence. It does not fail on the database
  policy with no explanation, and the tests watched that failure happen
  (`403`). One value per field per record means whoever seals first owns that
  slot. Sharing a sealed value between members is the "later phase" Doc 12 §2
  already names. It is not built.
- **Guest links** already clamp `sealed_values` through
  `app.guest_scope_allows`. **Emergency access** reaches these rows exactly when it
  reaches the record, and gets ciphertext it cannot open (§1.2).

## 6. Decision (f): what the server stores and may reveal

| the server may know | the server never holds |
|---|---|
| that a record **has** a location and/or a key holder (row presence) | the location |
| when each was last written, and by which user | the key holder's name |
| ciphertext length, so roughly how long the sentence is | anything derived from either: no hash, no hint, no index |
| the record's title, type and visibility, which were already plaintext | the passphrase or the content key |

Presence is deliberate. A future readiness score ("7 of 12 records say where the
original is") needs it, and it can be computed without unlocking. The index
returns it, and the locked screen shows it. Everything in Doc 12 §9 "Metadata"
applies here unchanged.

The audit log records `e2e.seal` / `e2e.unseal` with the field key. It does not
record the value, because the server never has it. Error payloads never echo the
submitted ciphertext either, because a buggy client might have sent the plaintext
in it. Both are tested.

## 7. What the server now refuses

A sealed value must be shaped like a Doc 12 §3 envelope. The server rejects
anything that is not, with `400 not_ciphertext`:

- fewer than **33 bytes** decoded (1 + 4 + 12 header, 16 tag). The old floor was
  17, which is a header with no tag. It accepted any 23-character run of letters
  that happened to be valid base64, such as `KeyWithAmmaAtSBIAmeerpet1`;
- a version byte other than **1**. Words decoded as base64 almost never start
  with `0x01`;
- a key version below **1**.

Underneath the service, the table has
`ciphertext_is_at_least_an_envelope check (length(ciphertext) >= 44)`. It is
`NOT VALID`, so it binds new writes without failing on old development rows.

The cost of checking the version byte: a future envelope format will need a
server release that accepts it. It would need coordinated client releases anyway.

## 8. Web client

- **Where it is** (`#/where`, also under More on a phone). It shows presence while
  locked, with an inline unlock. After unlocking it shows a search box, a filter
  for records with a gap, and results with the first match marked. Selecting a
  result opens the editor. **Lock** drops the opened values.
- **A card on each record** (holding detail, loan detail, account detail, and
  "Where the original is" on each will under *For my family*). It shows the two
  lines once unlocked and "Recorded — locked" before that. There is no
  legacy-note warning any more: the note cannot exist (§1).
- **Capture and the new-will form** do not ask where the original is. Each shows
  a line pointing to the sealed card instead (§1).
- **The editor** has two lines. Each is sealed on the device before it is sent,
  exactly as typed, with no trim. A blank line removes that field. Names are
  suggested, never linked. Each line has its guidance under it (§4).
- **The privacy notice** (Settings, and the end of onboarding) carries the
  key-holder paragraph ([Doc 23](23-privacy-notice.md)).
- **Strings** are in English, Telugu and Hindi (`where.*`, `nav.where`). The
  caveats are server sentences, and they stay English (Doc 14).
- The service worker version is bumped so the new modules are fetched.

## 9. What is verified, and what is not

**Verified by `WhereAndWhoApiTest`.** Each of these was watched failing with the
guarantee removed, then passing once it was restored:

| guarantee | how it was made to fail |
|---|---|
| a value not shaped like an envelope is refused (text, a 23-byte base64 word, 32 bytes, version 0/2, key version 0) | service floor put back to the old 17-byte base64 check: "a long run of letters" was stored `200` |
| the table refuses a value under 44 characters | constraint dropped on the test database: the insert succeeded |
| the AAD binds the field key, record, record type and household | reference AAD built without the field key: a location moved into `key_holder` opened |
| another member never sees a private record's location, in the index or the raw list | index query run on the owner pool (no RLS): the spouse saw the private entry. `linked_record_visible` replaced with `true`: the raw list leaked every private record's values |
| a shared record's slot cannot be overwritten by another member, and the refusal says why | check removed: `403` with no explanation instead of `409 sealed_by_someone_else` |
| rotation through `/e2e/key` carries both fields byte for byte, both open under the new passphrase, and the old one no longer unwraps | rotation made to delete the caller's sealed values: the before/after comparison failed |
| `document` records take sealed values | `document` removed from the service vocabulary: `400` on sealing |
| neither sentence appears in `sealed_values` or in `activity_log` | failed for real during the version-byte watch (a text-shaped value was stored), then scoped to the test's household |

**Verified by hand, in the browser against a local server.** The web client
sealed a location and a key holder on a locker. The rows were ciphertext in the
database. Search found the locker by "almirah", "AMEERPET" and "amma", found
nothing for an unrelated word, and no request URL on the page contained any of the words. The
move-and-clear button sealed "Home locker" and emptied `storage_location`. Lock
removed the words from the page. Blanking a line removed that field, and the
presence count updated after the save. The screen rendered in Telugu and Hindi.
The existing Doc 12 conformance vector still passes in that browser.

**Verified after review, in the browser against a local server.** The setup was
two members, one shared gold holding with the plain note "Steel almirah, second
shelf", and a location sealed on it by the other member. With the first version
of the button, the card showed "Sealed by someone else" and still offered **Seal
it, and clear the unsealed note**. Clicking it emptied `storage_location` and
sealed nothing, and the slot was still the other member's. With the fix, the same
card offers no button and says the note stays. On a private holding with an empty
slot, the button is offered. Clicking it sealed the note as this member's value
and emptied the column. The capture form for Physical Gold and the new-will form
render the pointer line and no location input. A `scripts/check-where-legacy.js`
harness asserted the button's rule for every slot state, and failed when the rule
was put back to "clear whenever unlocked". The button, its rule and the harness
were removed in V33's change, with the columns they moved notes out of; they are
in the history at `02a198d` for anyone who has to move notes first (§1).

**Verified for the retirement (V33).** `PlaintextLocationRetiredApiTest` covers
each server path in §1: a holding, an edit, a template (field, attributes, edit),
a will (create, edit) and a custom field or custom type sent with a location are
refused with `plaintext_location_retired` and no echo, and nothing is saved; an
empty value is accepted; a duplicate, a template saved from a holding and
applied, the handbook and the transmission guide carry no location; no seeded
type asks for it; the three columns do not exist; and the database refuses a type
or an attributes object that tries to bring it back. The migration itself was run
by hand against copies of the test database: with 13 holdings and 26 wills
holding notes it refused, named the counts and changed nothing; with them
emptied it dropped the columns and left every type's other fields in order. The
built server was started against a copy holding one note: startup failed with
the counts and the hint in the log, the log did not contain the note, and the
database was still at V32 with the note intact. With the note emptied, the same
jar migrated to V33 and started. In a browser against it, the Physical Gold
capture form had no location input and ended with the pointer line, a holding's
detail showed no "Kept at" row, the editor showed both helper texts in English,
Telugu and Hindi, and a request with `storageLocation` got
`400 plaintext_location_retired`. Which guarantees were watched failing is
listed in the change's report, not repeated here.

**Not verified:**

- **The native app has no editor for these two fields.** It is not broken: the app only lists
  and opens sealed values generically, and the only envelope rule tightened is
  one its own writer already satisfies. It has a generic sealed-field screen,
  which now shows the key-holder and location guidance when those field keys are
  typed; that screen was compiled for Android and the iOS simulator and its
  wording unit-tested, but not run on a device.
- The layout, by eye. The DOM was driven by script in a browser pane that was not
  on screen, so there are no screenshots, and nothing was seen at phone width.
- Typing a passphrase into the unlock form. The vault was created and unlocked by
  calling `e2e.js` from the page, and the form's own submit path was not
  exercised.
- Search performance at thousands of records.
- A trusted contact under an open emergency window seeing "locked" for these
  fields. This follows from V20 plus V22 and is not tested end to end.
- The DPDP question in §4.

[‹ Index](README.md)
