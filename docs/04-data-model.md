[‹ Index](README.md) · [‹ Prev: Screens & Flows](03-screens-and-flows.md) · [Next › Security & Privacy](05-security-and-privacy.md)

# 04 · Data Model

**Engine:** PostgreSQL. UUID PKs (`gen_random_uuid()`), `created_at`/`updated_at timestamptz`, soft-delete via `deleted_at`, `citext` emails, `numeric(18,4)` for money/quantity (never floats), lookup tables for enums. Tenancy key: nearly every table carries `household_id` for clean row-level security. **New in this revision:** a `visibility` axis on user-owned records powering [intra-household privacy](05-security-and-privacy.md#3-the-intra-household-privacy-model).

## 1. Entity overview
```
users ─< household_memberships >─ households ─< members
households ─< institutions ─< accounts ─< account_holders >─ members
accounts ─< investments ─< investment_ownerships >─ members
investments ─< investment_nominees / valuations / transactions / tax_lots / reminders
investments >─ investment_goals ─< goals
investments <─ asset_liability_links ─> liabilities ─< liability_holders
{investments,liabilities,accounts,goals,documents} ─< record_visibility_grants >─ members   (scoped visibility)
{investments,liabilities,accounts,members,estate} ─< document_links >─ documents (versioned)
households ─< custom_types ─< custom_fields
households ─< contacts ─< contact_links ;  households ─< estate
households ─< tax_summaries ; households ─< shares ; households ─< trusted_contacts ─< access_grants
households ─< invitations / activity_log / notifications / settings / exchange_rates
```

## 2. Auth & tenancy
- **users** — id · email citext unique · phone unique null · full_name · password_hash (Argon2id) · auth_provider · mfa_enabled · mfa_secret_enc bytea · locale · currency_pref · unit_pref · status · last_login_at · ts. *Idx:* unique(email); unique(phone) where not null.
- **households** — id · name · base_currency · **default_visibility** `private|household` · created_by→users · plan · ts. *Idx:* (created_by).
- **household_memberships** — id · household_id · user_id · role `owner|admin|editor|viewer|restricted` · scoped_member_ids uuid[] null · status · ts. *Idx:* unique(household_id,user_id); (user_id); (household_id,role). **Role = capabilities only; it does NOT grant visibility into others' private records** (see Doc 05).
- **members** — id · household_id · user_id null · display_name · relationship · date_of_birth · is_minor (generated) · avatar_url · notes · deleted_at · ts. *Idx:* (household_id) where deleted_at null; (user_id) where not null.
- **invitations** — id · household_id · email · role · token_hash · invited_by · expires_at · accepted_at · created_at. *Idx:* unique(token_hash); (household_id).

## 3. Reference / lookup
- **institutions** — id · household_id null(=global) · name · kind `bank|amc|broker|insurer|post_office|govt|exchange|lender|other` · logo_url · website · ts. *Idx:* (household_id); (kind); trigram(name).
- **asset_categories** — id · code · label · color · sort.
- **investment_types** — id · category_id · code · label · icon · schema_key · is_custom · household_id null. *Idx:* unique(code) per scope.
- **tags** — id · household_id · label · color. unique(household_id,label).

## 4. Accounts & investments
- **accounts** — id · household_id · institution_id · account_kind `savings|current|demat|folio|wallet|locker|other` · label · number_masked · number_enc bytea null · ifsc null · notes · **visibility** `private|household|scoped` · deleted_at · ts. *Idx:* (household_id) where deleted_at null; (institution_id).
- **account_holders** — id · account_id · member_id · holder_type `primary|joint`. *Idx:* unique(account_id,member_id); (member_id). *(Supports joint accounts — replaces a single owner column.)*
- **investments** — id · household_id · type_id · account_id null · institution_id null · title · status `active|matured|closed|draft|archived` · invested_amount numeric null · currency · quantity null · unit null · cost_basis_method `fifo|average|manual` · start_date · maturity_date · **attributes jsonb** (type-specific + custom fields) · notes · is_in_continuity bool · **visibility `private|household|scoped`** · last_verified_at null · deleted_at · created_by · ts. *Idx:* (household_id,status); (type_id); (account_id); (institution_id); (maturity_date) where status='active'; (household_id,last_verified_at); (household_id,visibility); GIN(attributes); (household_id) where deleted_at null. *(A plaintext `storage_location` existed until V33; where the original is is a sealed value now — Doc 20.)*
- **investment_ownerships** — id · investment_id · member_id · holder_type `primary|joint|guardian` · share_pct numeric(5,2) check 0–100. *Idx:* unique(investment_id,member_id); (member_id). Sum=100 enforced app-side. *(A member who owns a record always sees it, regardless of visibility.)*
- **investment_nominees** — id · investment_id · member_id null · nominee_name null · relationship null · share_pct. *Idx:* (investment_id); (member_id).
- **valuations** — id · investment_id · as_of_date · value numeric · quantity null · source `manual|import|quote_api`. *Idx:* (investment_id, as_of_date desc). Current value via `investment_current` view.
- **transactions** — id · investment_id · txn_type `buy|sell|contribution|withdrawal|interest|dividend|fee|split|bonus|merger|buyback` · amount · quantity null · price null · txn_date · from_account_id null · lot_id null · notes. *Idx:* (investment_id,txn_date desc); (from_account_id).
- **tax_lots** — id · investment_id · acquired_on · quantity · unit_cost · remaining_qty · created_at. *Idx:* (investment_id, acquired_on).

## 5. Liabilities
- **liabilities** — id · household_id · institution_id(lender) null · kind `home|car|personal|education|gold|credit_card|lap|las|loan_against_insurance|family|other` · title · principal · outstanding · interest_rate · emi_amount null · emi_day int null · start_date · end_date null · status `active|closed` · attributes jsonb · notes · **visibility** · deleted_at · created_by · ts. *Idx:* (household_id,status); (institution_id); (emi_day).
- **liability_holders** — id · liability_id · member_id · responsibility_pct numeric(5,2). *Idx:* unique(liability_id,member_id).
- **asset_liability_links** — id · liability_id · investment_id · note. *Idx:* unique(liability_id,investment_id); (investment_id).

## 6. Goals
- **goals** — id · household_id · name · target_amount · target_date · priority · member_id null · icon · status `active|achieved|archived` · **visibility** · ts. *Idx:* (household_id,status).
- **investment_goals** — id · goal_id · investment_id · allocation_pct numeric(5,2). *Idx:* unique(goal_id,investment_id); (investment_id). (Per-investment allocation ≤ 100%, app-enforced.)

## 7. Universal engine
- **custom_types** — id · household_id · code · label · icon · color · category_id null · version · created_by · ts. unique(household_id,code).
- **custom_fields** — id · household_id · owner_type `type|record` · owner_id · key · label · data_type `text|number|money|date|percent|bool|select` · unit null · options jsonb null · required · sort. Values live in the record's `attributes` JSONB keyed by `key`; definitions versioned.

## 8. Privacy / visibility (NEW)
- **record_visibility_grants** — id · household_id · record_type `investment|liability|account|goal|document` · record_id · member_id · created_by · created_at. *Idx:* (record_type,record_id); (member_id). Present only when a record's `visibility='scoped'`; lists exactly which members may see it. Combined with `visibility` and ownership, this is the single source of truth for who-sees-what, enforced by RLS (see [Doc 05](05-security-and-privacy.md)).

## 9. Proof, reminders, tags
- **documents** — id · household_id · storage_key · file_name · mime_type · size_bytes · doc_type `certificate|statement|receipt|policy|deed|photo|kyc|will|other` · version · supersedes_id null · expires_on null · **visibility** · ocr_status · uploaded_by · created_at. *Idx:* (household_id); (doc_type); (expires_on) where not null.
- **document_links** — id · document_id · entity_type · entity_id. *Idx:* (document_id); (entity_type,entity_id). *(One statement can cover several records.)*
- **reminders** — id · household_id · investment_id null · liability_id null · kind `maturity|premium_due|renewal|sip|emi|review|verify|custom` · due_date · lead_days · recurrence · status · note · ts. *Idx:* (household_id,due_date); (status,due_date).
- **investment_tags** — unique(investment_id,tag_id).

## 10. Estate, contacts, tax, sharing, security, ops
- **estate** — id · household_id · member_id · has_will · will_location · executor_contact_id null · poa_contact_id null · notes · ts. *Idx:* (household_id,member_id).
- **contacts** — id · household_id · name · role `ca|agent|lawyer|banker|advisor|doctor|other` · phone · email · firm · notes · ts. **contact_links** — id · contact_id · entity_type · entity_id.
- **tax_summaries** — id · household_id · member_id · fy · deductions jsonb · realized_gains jsonb · interest_income · generated_at. unique(household_id,member_id,fy).
- **shares** — id · household_id · created_by · scope `continuity|tax_pack|report|investment` · target_id null · token_hash · permissions `read` · expires_at · revoked_at null · last_accessed_at · created_at. unique(token_hash).
- **trusted_contacts** — id · household_id · grantor_user_id · contact_member_id null · contact_email · unlock_after_days · scope `continuity_summary|full_read` · status · created_at.
- **access_grants** — id · trusted_contact_id · requested_at · unlocks_at · state `requested|vetoed|unlocked|expired` · vetoed_at null · created_at. *Idx:* (state,unlocks_at).
- **exchange_rates** — id · base · quote · rate · as_of_date. unique(base,quote,as_of_date).
- **activity_log** — bigint id · household_id · actor_user_id null · action · entity_type · entity_id · diff jsonb null · ip · user_agent · created_at. Append-only. *Idx:* (household_id,created_at desc); (entity_type,entity_id).
- **notifications** — id · user_id · channel · template · payload jsonb · sent_at · read_at · status.
- **settings** — scope `household|user` · scope_id · key · value jsonb. unique(scope,scope_id,key).
- **encryption_keys** — id · household_id · key_version · wrapped_dek bytea · created_at. KEK in KMS, never in DB.

## 11. Integrity & derived views
Money/quantity are `numeric`. Share/responsibility/allocation sums enforced app-side + deferred triggers. Soft-delete → Trash/Restore; hard purge separate, logged, honors erasure (§12). `investment_current` = latest valuation per investment. `household_net_worth` = Σ(asset value × share) − Σ(active liability outstanding). **Per-viewer** totals are computed through the visibility filter (Doc 05), so each member's number reflects only what they may see; owners see their true totals.

## 12. Lifecycle: closing, passing away, leaving, succession, coming of age (V40, V41)
All five follow [Doc 05 §12](05-security-and-privacy.md#12-the-end-of-an-account-and-the-changes-in-between). Each has RLS, a trigger that limits what may change once a row exists, and a waiting period stated as a check constraint so no client can shorten it. The sweep that carries them out (`LifecycleSweep`) runs on the owner connection.
- **account_closures** — id · user_id→users null (set null by the purge) · requested_at · **purge_after** (check ≥ requested_at + 30 days) · cancelled_at · purged_at · created_at. *Idx:* unique(user_id) where pending; (purge_after) where pending. Readable and cancellable only by the person; dates immutable; `purged_at` written only with no user (the sweep). The row outlives the account as the record that the erasure happened.
- **member_memorials** — id · household_id · member_id · user_id null (the login, if any) · marked_by · **basis** `admin|trusted_contact` · note ≤500 · marked_at · reversed_at · reversed_by. *Idx:* unique(member_id) where not reversed. Insert: an admin, or the caller's open emergency window on that member (`app.has_open_emergency_window`); never yourself. Update: only the person named, only to reverse. `app.is_memorialised_in` makes `can_write_household` and `can_administer_household` false for them; `app.notifications_stopped` makes `record_in_app_message` and `enqueue_outbound_message` write nothing except `lifecycle.memorial.marked`.
- **household_departures** — id · household_id · member_id · user_id · started_by · **started_by_admin** · **private_records** `take|export_and_erase` · requested_at · **effective_at** (check ≥ requested_at + 7 days) · cancelled_at · completed_at · destination_household_id null. *Idx:* unique(member_id) where pending; (effective_at) where pending. Readable by the household (the roster is about to change); only the leaver sets `private_records`; whoever started it cancels; only the sweep completes.
- **departure_joint_decisions** — departure_id · record_type `investment|liability|account` · record_id · **decision** `stays|take_my_share` · decided_at. PK(departure_id, record_type, record_id). Written only by the leaver about a record they hold (`record_holder_member_ids`); read by the leaver and by anyone who can already see the record.
- **household_successors** — household_id (PK) · named_by→users · successor_member_id · named_at · claimed_at · claimed_basis `passed_away|emergency_access`. Readable by the owner and the person named; written by the owner. Claimed only through `app.claim_household_succession`: owner memorialised ≥ 7 days → owner, or the successor's open emergency window on the owner → admin.
- **coming_of_age_notices** — member_id (PK) · household_id · turns_adult_on · noticed_at · welcomed_at. Written by the sweep in the birthday month; readable by the household; the young adult sets `welcomed_at`.
- **Helpers** — `app.managed_member_holdings(member)` → (visible_count, hidden_count) and `app.recorders_of_hidden_holdings(member)` → user ids: counts and people, never titles, for an admin removing a managed member.
- **Erasure and moves write around RLS on purpose** (owner connection, one transaction): a closure hard-deletes what the person solely holds and passes their share of joint records on; a departure moves solely held rows to `destination_household_id` (arriving `private`, custom types and institutions copied, server-encrypted fields re-encrypted for the new household first, sealed values dropped). `activity_log` rows are kept: actor set null, and detached from a household before that household is deleted.

[‹ Index](README.md) · [‹ Prev: Screens & Flows](03-screens-and-flows.md) · [Next › Security & Privacy](05-security-and-privacy.md)
