[‹ Index](README.md) · [‹ Prev: Data Model](04-data-model.md) · [Next › Backend, API & Stack](06-backend-api-and-stack.md)

# 05 · Security & Privacy  ★ priority

**Guiding goal:** *A breach should reveal as little as possible, and Almira must never be where an attacker looks for bank passwords — because they aren't there.* Security here isn't a feature; it's the product's license to exist. People will only pour their entire financial life into something they trust more than a locker.

## 1. Threat model — what we defend against
Account takeover (credential stuffing, phishing) · database exfiltration (a dump must not reveal usable secrets) · broken access control (cross-household **and cross-member** leakage) · document leakage · a rogue insider · a lost/stolen device with an open session · abuse of scoped guest links · **coercion/surveillance of a vulnerable family member** (a first-class threat here, see §3).

## 2. Authentication & session
- Phone (or, in the closed alpha, email) + one-time code; no passwords. SSO (Google/Apple) is not built.
- **Second factor** (built, V50): an authenticator app (RFC 6238, secret envelope-encrypted per person), **passkeys** (WebAuthn, off until a domain is configured) and single-use **recovery codes** (PBKDF2, shown once). Once an account has one, a correct one-time code is **not enough to sign in**: it earns a five-minute token, and the session starts only after the factor — the defence against a disconnected number reassigned to a stranger, or a swapped SIM. Five wrong answers end that sign-in; ten an hour stop the account.
- **How you sign in** asks for at least two ways in (phone, email where offered, authenticator, each passkey), and refuses removing one that would leave fewer than two. Adding, replacing or removing a factor on an account that has one needs the factor itself.
- **Changing the phone number** needs a signed-in session confirmed by the old channel or a second factor, then a code to the new number; a number held by another account is refused only after its code is proven.
- **Every sign-in to an existing account, and every change to how it signs in, is audited and announced** to the in-app list every device reads and through the notification outbox ("New sign-in on Chrome on Android"). No location is shown: that would need a lookup this server does not make.
- Step-up (re-authentication on this session) before full numbers or documents, by a code or any second factor. Making a second factor *required* for emergency-access grantors is still to do.
- Short-lived access tokens + rotating refresh tokens; device/session list with remote revoke.
- **Mobile:** biometric app-lock (Face/Touch), secrets in Keychain/Keystore, auto-lock on background, no sensitive data in logs; optional jailbreak/root signal.

## 3. The intra-household privacy model  ← the answer to your question
**Principle:** *Joining a household never means surrendering financial privacy.* Almira keeps two axes completely separate:

- **Role = capabilities** (invite members, manage roles, edit shared records, billing). Roles are `owner · admin · editor · viewer · restricted`.
- **Visibility = which records you can see.** Governed per-record, **not** by role. **No role — not even owner or admin — can see another member's Private records.** Being the household creator lets you *manage the household*, not read everyone's private holdings.

### 3.1 Per-record visibility levels
Every user-owned record (investment, liability, account, goal, document) carries a `visibility`:
- **Private** — visible only to its **owner(s)** (the members on its ownership/holder list). Not to admins, not to other members.
- **Household** — visible to all active members of the household.
- **Scoped** — visible to specific members listed in `record_visibility_grants` (e.g., share this FD with your spouse but no one else).

**Joint records override "private" for co-owners:** you can't hide a jointly-owned asset from your co-owner — all owners always see records they co-own.

### 3.2 Defaults (a household decides its culture)
- A **household default** (`private` or `household`) set at creation, changeable later, and a **per-user default** that wins for that user's new records.
- Sensitive-by-nature items (e.g., personal insurance, a private savings buffer) **nudge toward Private** at capture, with one tap to share.
- Recommended default for trust: **Private-by-default with an easy "Share with household" toggle.** Transparent families can flip the household default to `household`.

### 3.3 Totals never leak
Each viewer's household net worth is computed **through their own visibility filter** — it sums only what they may see. The **owner** of private items sees the true total; **others** see the shared-only total. A private item contributes **nothing** (not even its amount) to another member's view. *(An advanced future option — "count in the household total but hide details" — is deliberately deferred, because it leaks the amount; v1 keeps the clean, honest Private/Shared/Scoped model.)*

### 3.4 Reconciling privacy with continuity
Privacy is *for life*; continuity is *for after*. A record can be **Private now** and still `is_in_continuity = true`, meaning it appears in the "For My Family" view **only when emergency access unlocks** (§6). So a member keeps something private from the family while alive, yet the family still isn't left guessing later. Privacy now, disclosure on the defined event — the two goals coexist.

### 3.5 Safety for vulnerable members
This model is also a safety feature. Because no role is omniscient, the app can't be weaponized for financial surveillance or coercion: a spouse/parent can't silently see another adult's private holdings. Visibility changes and any permitted cross-member view are **audited**; granting/revoking access notifies the affected member; and anyone can **leave a household with their own data** (§8, §12). Minors/managed members are visible to their guardians by design; when a minor gains their own login at adulthood, control transfers to them (§12.5).

### 3.6 Enforcement (defense in depth)
Encoded at **three layers** so a bug in one can't leak data:
1. **Postgres Row-Level Security** — the authoritative gate. A member may read a record iff:
   `is an owner/holder` **OR** `visibility='household'` **OR** (`visibility='scoped'` AND a matching `record_visibility_grants` row) **OR** (`emergency access unlocked` AND `is_in_continuity`).
2. **Service layer** re-checks the same predicate and applies field-masking (last-4).
3. **UI** never requests or renders what the API won't return.
Search, reports, document access, and exports all pass through the **same** visibility predicate — there is no side-channel.

### 3.7 Visibility × Role matrix (reading)
| | Own records | Household-shared | Scoped-to-me | Others' Private | Continuity (pre-event) | Continuity (post-unlock) |
|---|---|---|---|---|---|---|
| Owner/Admin | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Editor/Viewer | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Restricted | ✓ | scoped | ✓ | ✗ | ✗ | ✓ (if trusted contact) |
| Guest (link) | — | slice only | — | ✗ | link scope only | — |
*Roles still differ on **capabilities** (edit/manage) — see [Doc 01 §11](01-product-and-scope.md) and Backend RBAC.*

### 3.8 Previewing another member's view ("What Ravi sees")
The Family screen can show Home as another member sees it. It must leak in neither direction, and it is built so that it cannot:
- **One side only.** The server reads, as the viewer and by ordinary sight, the investments and debts the viewer can see. The other member's private records are never read, so they cannot appear as a title, a count or part of a total.
- **One yes or no per record.** `app.member_would_see(member, type, id)` (V81) says whether that member would read the record: an active membership; an advisor sees explicit grants only; everyone else sees what they hold, what is household-shared and what is scoped to them. It is SECURITY DEFINER because a liability's grants are readable only by the member they name — so it **answers false for any record the caller cannot read**, and cannot be used to probe what someone else holds. What it can say is already on the record's "Who can see it" line.
- The viewer's private records show as "not in Ravi's view", never as something he sees. A member with no sign-in sees nothing. Emergency access is not previewed.
- Each preview is audited (`member.preview`, the member's id, nothing it contained). `db/tests/rls_privacy_test.sql` and `MemberPreviewApiTest` hold both directions.

## 4. Encryption
- **In transit:** TLS 1.3, HSTS.
- **At rest:** volume encryption + **field-level encryption** for the most sensitive columns (account/policy numbers, `mfa_secret`) via **envelope encryption** — per-household **DEK** wrapped by a **KEK** in a managed **KMS**; the DB stores only the wrapped DEK, never the KEK.
- **Documents** encrypted before object storage; access only via short-lived signed URLs after re-auth, and only if the caller passes the §3 visibility check.
- **Optional zero-knowledge mode** (privacy-hawk tier): sensitive fields + documents encrypted with a key derived from the user's passphrase; server stores only ciphertext. Honest trade-offs shown: no server-side OCR/search on those fields, and no password-reset recovery of that data.

## 5. Data minimization & masking
Store **masked** (last-4) by default; full numbers only on explicit opt-in, then field-encrypted, and viewing them requires re-auth/MFA. **Never store bank/broker passwords or OTPs — ever.** Redact sensitive values from logs, errors, analytics. Collect the minimum; every field justifies itself.

## 6. Emergency (continuity) access — safe by design
Trusted contact + **time-delayed inactivity unlock** (configurable window), **vetoable by the owner at any point** during the window, with notifications to both parties throughout, then a **scoped read** (continuity summary or full-read) — all logged. No silent backdoor; the delay + veto + audit make it both humane and safe.

**Shown as a dated picture** (X-41): every request carries the same five steps — asked, told, days to say no, sees the family plan, closes — and naming someone previews them dated as if they asked today, with what would and would never be seen, in counts. **Heir mode** (X-40, V90) turns an open window into a task list for the person who asked. Its plans, tasks and helpers are readable only by that person and only while `app.has_open_emergency_window` holds, so they close with the window and no worker is involved. A task stores a record id, never a copy of its title, and carries no amount. Handing a task to a relative issues a guest link of scope `heir_help` (§7) that names only the records of the tasks handed over, opens only `/api/v1/share/<token>/tasks`, and inside that guest session the policies narrow the tasks to that helper's own; at most five at a time, enforced by the service and a trigger. A member with an open window may make such a link whatever their role; nothing else about who may make links changed. Starting a plan, adding or removing a helper, and handing over a task are audited. **Known limit** (known issue 51): an open window reveals continuity-marked records across the household, not only the subject's; heir mode's tasks are narrowed to the subject's own records, but the ordinary endpoints are not.
As built: a request starts when a named contact asks (V20), and opens only if the person it concerns has shown no sign of themselves since (V25). An owner may also turn on **"if I go quiet"** — off by default, a step-up to turn on — which after two unanswered check-ins raises that same request in each contact's name; a sign-in or a one-tap "I'm here" keeps the window shut, and "I'm here" vetoes what it raised ([Doc 27](27-continuity-signals.md) §1–2).

## 7. Scoped external sharing (guest links)
Single-scope, read-only, expiring, revocable, rate-limited, fully audited links (e.g., the FY tax pack to a CA for 7 days). Opening a link never exposes anything outside its slice; sensitive documents can be excluded per share; the link resolves to a least-privilege guest principal that still passes the §3 predicate for its scope. A tax-pack link may name one taxpayer (`scope_member_id`); its PDF and Schedule 112A CSV are served behind the same token (`/api/v1/share/{token}/tax-pack.pdf`, `/schedule-112a.csv`), each opened exactly like the link — counted, audited, and rendered inside the clamped read-only guest session ([Capital gains for a CA](tax/capital-gains.md) §5).

**The page a link opens** is `static/guest.html` at `/share/<token>` (and `/help/<token>` for a heir-mode helper): it holds no data, sends no Referer, stores nothing, and reads the slice from the endpoint above. **The envelope edition of the handbook** (P-28, V93) makes a handbook link that also names the debts, paperwork and contacts the printed pages carry, lasts **365 days** rather than the ninety a person may choose, needs a step-up, and withdraws the same person's previous edition's link when a new one is printed; `handbook_editions` is readable only by its maker. **The emergency kit**'s QR code is the app's address and carries no token. **Lost-money checks** (V92) are readable by whoever recorded them and the member they are about, and never from a guest link; the portals are links only, with no outbound call. **Guided-flow drafts** (V91) are their user's own, and a where-and-who draft cannot hold words by constraint.

## 8. Compliance & data rights
**India DPDP Act 2023** alignment: purpose limitation; **consent when storing another adult's data** (a co-managing invite records consent); **a parent's recorded consent** for minors (§8.1); data-principal rights as product surfaces (§8.1). GDPR-style **export** (machine-readable: "Download everything", §12.6) and **erase** (hard purge with audit: closing an account, §12.1). Clear data-ownership terms for shared households and for a **departing member** (§12.3: their own records go with them or are erased after their copy, at their choice; what they hold jointly is theirs to decide about; private records go with their owner, and no admin sees what they are). Security and audit logs are kept for at least a year after an erasure (DPDP Rules 2025, rule 8(3)). Encrypted, tested **backups**; documented DR (RPO/RTO). Future auto-import only via India's **Account Aggregator** (consent-based, read-only) — never scraping or credential harvesting.

### 8.1 What exists for the DPDP Act and Rules (V45)
The core obligations commence on **13 May 2027**; this is built ahead of them and is not a legal opinion (the notice is still unreviewed, [Doc 23](23-privacy-notice.md)).
- **Itemised consent, withdrawal as easy as giving.** `consent_events` is an append-only history per person, per purpose (`records`, `messages`); the current choice is the latest event. Giving and withdrawing are the same call (`POST /api/v1/me/privacy/consents`). `records` is the service itself: withdrawing it is closing the account, and the API says so (`withdraw_by_closing`). `messages` is **opt-in** (V125): no event is a no, nobody was migrated into consent, and a yes names its channels and where it was asked. `app.enqueue_outbound_message` asks `app.messages_consent_given(user, channel)` **before** it writes a row, so nothing but an essential account or security notice is ever queued by email, SMS or push without a yes on that channel, and the worker asks again as it claims a row (`no_consent`); in-app copies are unaffected. The essential notices — sign-in codes, a new sign-in, a phone or factor change, emergency access requested, raised in your name or stopped, closure, being marked passed away, a household taken over, being asked to leave — are one list, `app.message_is_essential`, used by queueing, the worker and pacing alike ([Doc 23](23-privacy-notice.md) "Notices that protect your account"). The question is asked at the moment a reminder would first help, and "Not now" keeps it away for 90 days without recording consent (Doc 23 "Asked when it helps").
- **Notice versions.** `privacy_notice_versions` is published by migration only; `privacy_notice_acceptances` records which version a person accepted, and accepting a version no longer in force is refused.
- **Grievance contact and response period.** `almira.privacy.grievance.{name,email,response-days}`; every rights reply carries it. The period is capped at 90 days in configuration (refuses to start) and in the database (a request's `respond_by` cannot be later).
- **Rights requests.** See (s.11) is answered on the spot by `GET /api/v1/me/privacy/summary`: counts of what is about the caller — never amounts or titles — the purposes, and who else can see it. Correction and complaints are `data_rights_requests` with a reply-by date. Erase is the account-closure flow (§12.1), which the Erase card opens.
- **Nomination (s.14).** `data_rights_nominees`, one or more people who may exercise the person's rights on death or incapacity. Naming one needs a step-up. Deliberately not the emergency contact (§6): that is a household member who may ask to *read* marked records after a wait; a nominee acts on the person's *rights*.
- **Children (s.9, Rule 10).** Adding a minor with no login asks the adult to record consent as the child's parent or lawful guardian: a declaration and a one-time step-up code, then a dated line on the child's profile (`parental_consents`, visible to whoever sees the child; withdrawn only by the adult who gave it). There is **no analytics or tracking of any kind**, so none touches a child's records; `NoTrackingTest` keeps it that way. What the code proves is who is signed in, not adulthood — see known issues.
- **Privacy of the records themselves.** Every table above has RLS scoped to the person it is about, withdrawals go through narrow definer functions, and the assertions are in `db/tests/rls_privacy_test.sql`.

## 9. Auditing & monitoring
Append-only `activity_log` for writes, sensitive views, exports, logins, share creation, visibility changes, and access-grant transitions. Rate limiting; anomaly alerts (impossible travel, mass export); WAF; dependency/secret scanning; least-privilege service accounts; secrets in a manager, never in code.

## 10. Concurrency & sync
**Optimistic concurrency:** writes carry the record's `version`/`updated_at`; a stale write is rejected with the current state so the client can merge or prompt — never a silent overwrite. Offline captures use idempotency keys; sync conflicts surface a clear "keep mine / keep theirs / merge" choice. All resolutions land in the audit diff.

## 11. Secure SDLC
Threat-modeling per feature; code review; SAST/DAST; dependency and container scanning; signed builds; staged rollouts; periodic third-party penetration test and a **public security whitepaper** (trust is marketing here — see [Doc 08](08-differentiation-and-standout.md)).

## 12. The end of an account, and the changes in between

People close accounts, die, leave households, stop being able to manage them, and turn eighteen. Each of these changes who holds what, so each is **slow on purpose**, **said in plain words before anything happens**, **reversible while it waits**, **audited**, and **enforced in the database** rather than trusted to a client. None of them widens what anyone can see: role is still capability, never sight (§3).

### 12.1 Closing an account (erasure)
- **Order.** The person is offered their copy first (§12.6) and the family handbook. Then a preview in two columns — *erased* and *stays with the household* — then the date in plain words. `POST /me/closure` needs a step-up.
- **Thirty days.** `account_closures.purge_after` must be at least thirty days after the request (a check constraint), and a trigger refuses any change to the dates. *Keep my account* cancels it. Messages keep arriving meanwhile: the person may yet stay.
- **Nobody is left without someone to run a household.** An owner who is the only one who could run a household where others sign in is asked to name a successor (§12.4) or make someone an admin first. At purge, the last owner of a household that still has other people **and records** in it is never purged: the household goes dormant and waits for someone to take it on (§12.7), and the rest of the closure is carried out. Where there are no records, the owner role passes to the named successor, else the longest-standing admin, else the longest-standing member.
- **What is erased.** The sign-in, profile, sessions and messages; every record the person *solely* holds (every holder row names them), whatever its visibility, with the documents that go only with those; their sealed values, guest links, templates and emergency requests; and every household nobody else signs in to, with everything in it.
- **What stays.** Joint records, with the person's share passed to the other holders in proportion (the share-sum constraints still hold at commit). Records they typed in for other people, without their name. Other people's records that named them — a nomination, a role in a will — keep the name as text: those are the other people's records.
- **What is kept, and why.** The audit log, for at least a year (DPDP Rules 2025, rule 8(3)): rows lose their actor, and a household's own rows are detached from it before it is deleted so the delete cannot cascade them away. The application cannot edit or delete audit rows in any case. The closure row stays with `user_id` null and `purged_at` set, as the record that the promise was kept. The screen says all of this.
- **How.** The lifecycle sweep (`LifecycleSweep`, hourly) purges on the owner connection, one transaction per account; stored document bytes are deleted and sessions revoked only after it commits. A closure held by a dormant household stays pending, the account and its sessions stay, and *Keep my account* still works until the purge is carried out.

### 12.2 Someone has passed away
- **Who may say so.** An admin of the household, or the person's trusted contact once an emergency window on them is genuinely open (§6). Never yourself. A step-up either way; the audit records which door it came through.
- **What it does.** Nothing is erased and nothing is opened. The account keeps sight of what it could see in that household and loses every capability there: both capability functions refuse a memorialised caller (V40), so every write policy does, and the API says `memorial_read_only` in words. The roster carries a quiet *In memory* label. Messages stop where every message is written (`record_in_app_message`, `enqueue_outbound_message`).
- **The safeguard.** The one message the stop lets through is the one telling the person they have been marked. Only they can take the label away — by signing in and choosing *I'm here* — which is also the plainest correction of a mistake, or of a lie.
- **Heirs.** What the family may see is unchanged: what was shared, plus what the person marked for continuity once emergency access opens. The Family screen points to *For my family*.

### 12.3 Leaving a household
- **Anyone can leave**, and an admin can ask someone with a login to (removing a person with a login is refused; see below). A step-up either way. Nothing happens for **seven days** (a check constraint); whoever started it can withdraw it, and an admin-started departure is not the leaver's to withdraw (a trigger).
- **What goes.** What the person solely holds, as in §12.1. **Their choice, and only theirs** (a trigger): *take* it into a household of their own, or *download, then erase*. Moved records arrive **private** — they were shared with people who are not in the new household. Server-encrypted fields (account numbers, documents) are re-encrypted for the new household, as the leaver, before the rows move on the owner connection. **Sealed values cannot move**: their AAD names the household (Doc 12) and the server cannot re-seal what it cannot read; they are in the person's download and the preview counts them.
- **What they hold with someone else** is listed for them alone to decide: *leave it with them* (their share passes on) or *take a copy of my part* (a private record in their own household sized to their share on the day; the joint record stays whole). A decision is visible to the other holders, who can already see the record (`departure_joint_decisions`, V41), and to nobody else.
- **Neutral words.** The household is told who is leaving and when, and later that they have left. Nobody is told what went.
- **The last owner.** An owner who leaves a household that still has other people and records in it does not leave it ownerless: when the seven days are up the household goes dormant (§12.7) before anything is prepared — no household of their own is made, nothing moves — and the departure waits until someone takes it on.
- **The admin removal dead end.** Removing a managed member used to count their holdings under the admin's own row-level security, so a record private to whoever added it counted as nothing and the member was removed from under it. Now `app.managed_member_holdings` counts visible and hidden records (never titles), the recorders of the hidden ones are told, and the removal waits for them.

### 12.4 When the owner can't manage it: succession
- The owner names a **successor** with a login (not an advisor). Only the owner and that person can see it.
- The successor can **claim** only when the named event has happened, checked in the database (`app.claim_household_succession`, V41): the owner has been marked as passed away **for at least a week** — so a false memorial can be undone before it hands a household over — in which case they become the owner; or the successor's own emergency window on the owner is open, in which case they become an admin. A step-up; audited.
- **It opens nothing.** The owner's private records stay private; what the family sees is what the owner marked for continuity, through the emergency window, exactly as they chose in advance.
- A memorial on the last owner of a household with records also makes it dormant (§12.7). A claim on that memorial ends the dormancy, as taking it on does.

### 12.5 Turning eighteen
- In the month a managed child turns eighteen the sweep writes one notice and tells the household's admins, so the ordinary invitation can follow — which claims the child's member row, so what is held in their name becomes theirs by the read rule (§3.1).
- Signed in as themselves, the young adult is welcomed once: *These are yours now*, the list, and a choice for each of whether it stays visible to the family or becomes private to them, made through their own row-level security. A parent who holds a record as guardian stays a holder until the record is edited.

### 12.6 Download everything
- One zip: a readable PDF (holdings and debts in Indian grouping, totals in words), the same data as JSON and as CSV per kind of record per household, and the documents the person can open, decrypted.
- **Exactly what they can see**: every query runs as them under row-level security. Full account numbers are left out (last four only; the app reveals one at a time after a step-up). Sealed values are included **as ciphertext**, with the wrapped key and the AAD rule, never as plaintext.
- Needs a step-up; audited with counts, never contents.

### 12.7 A household whose last owner goes: dormant until someone takes it on
The owner's decision (2026-09-15): *never purge the last owner while the household holds records. Move it to dormant, tell the remaining members, and require an explicit transfer before any purge. A family's records must not become unreachable because of an account lifecycle rule.*
- **When.** The person going is an active owner; no other owner who is not memorialised remains; someone else still has an active membership; and the household holds records (holdings, debts, accounts, goals, estate documents, contacts or papers, Trash included). Asked in one place, `app.going_leaves_household_ownerless` (V120). A household nobody else belongs to is not dormant: it is erased with its only person, as §12.1 says.
- **Asked before anything is carried out.** The purge asks it of every household before it erases or detaches anything; the departure sweep asks before step one. A memorial on such an owner makes the household dormant by a trigger. A dormancy row (`household_dormancies`) records the reason — `owner_closing_account`, `owner_leaving` or `owner_passed_away` — the date, and the closure, departure or memorial that caused it.
- **What waits.** For that household, everything of the owner's: their membership, what they hold there, and for a closure the account itself. The rest of a closure is carried out: households nobody else signs in to are erased, and the person leaves the others. The closure or departure stays pending.
- **While dormant.** Nobody can do what needs an owner or admin: `can_administer_household` and `is_household_owner` answer false for everyone, so inviting, removing, renaming, naming a successor or marking someone as passed away through the admin door are refused, and the API says `household_dormant` in words. Nothing else changes: every member sees exactly what they saw, members still add what is theirs, and the handbook and Download everything work. `GET /households/{id}` carries `dormant`; `GET /households/{id}/dormancy` says since when, why, whose going, and whether you may take it on.
- **Told, in neutral words.** Admins, editors and viewers with a login are told in the app and through the outbox (`lifecycle.household.dormant`, essential): who no longer runs the household, that nothing has been erased and private records stay private, and that an adult in the household can take it on. Never what anyone held, and never whether the person is closing their account. The owner whose closure or departure is held is told it waits (`lifecycle.household.dormant.you`). When it ends the household is told who took it on, or that it runs as before.
- **Ending it: an explicit transfer.** `POST /households/{id}/dormancy/accept`, with a step-up, through `app.accept_household_ownership`: an adult (not a minor) with an active membership as admin, editor or viewer — not an advisor, a restricted member, someone memorialised, the owner whose going caused it, or someone who is themselves closing their account or leaving this household. A week after a memorial (a false memorial must be correctable before it hands a household over, as §12.4), at once otherwise. The caller becomes owner; nothing else changes; audited as `household.ownership.accept` and `household.dormancy.end`.
- **Or the owner comes back.** *Keep my account*, cancelling the departure, or *I'm here* ends the dormancy (`owner_returned`) by a trigger. Nothing had been carried out in that household, so nothing needs undoing.
- **Only then.** The next sweep carries out the closure or departure as §12.1 and §12.3 say — including erasing what the departed owner solely held, whatever its visibility.
- **It opens nothing.** Dormancy and taking it on change capability, never sight (§3). The departed owner's private records are seen by nobody while it is dormant and nobody after, and are erased or moved by the rules above.
- **Nobody eligible.** A household with no one who may take it on (only advisors, restricted members, minors, or people memorialised) stays dormant. It is never erased for that, and the closure stays pending; see docs/known-issues.md, "A dormant household with nobody who may take it on waits for good".
- **Enforced in the database.** The table has row-level security (members read; nobody writes through a policy, and the runtime role holds no write privilege); the helpers that open, end or answer the rule about a named person are not executable by the runtime role. `DormantHouseholdApiTest` and `db/tests/rls_privacy_test.sql` hold both.

[‹ Index](README.md) · [‹ Prev: Data Model](04-data-model.md) · [Next › Backend, API & Stack](06-backend-api-and-stack.md)
