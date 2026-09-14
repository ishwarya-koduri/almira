# Capital gains for a CA

> **Informational, not tax advice.** Everything here describes how Almira
> arranges what a household has recorded. It is a starting point for a person
> or their chartered accountant, never a filing. Every response, file and page
> says so where the numbers are.

Refs: docs/01 §9, docs/10 Epic 2.3, docs/05 §7. Code:
`backend/.../tax/CapitalGainsRules.kt` (the rules),
`CostInflationIndex.kt` (the index), `CapitalGainsService.kt` (reads),
`Schedule112ACsv.kt`, `TaxPackPdf.kt`, and `db/migrations/V75__…`.
Tests: `CapitalGainsRulesTest` (every example below), `TaxPackFilesTest`,
`CapitalGainsApiTest`, and the V75 section of `db/tests/rls_privacy_test.sql`.

## 1. What it does

For a financial year, every recorded sale becomes one line per lot sold
(oldest units first, as the lot engine already matches them). Each line says:

- the asset class and whether it is short- or long-term, **by the rule on the
  date of that sale**;
- the section it falls under — 111A, 112A, 112, 50AA, slab, or 115BBH — and,
  from 1 April 2026, the section of the Income-tax Act, 2025 that replaced it;
- which side of 23 July 2024 it fell, because rates and holding periods changed
  that day, mid-year;
- the cost used: actual, grandfathered (31 January 2018), or indexed;
- the rate, the gain the rate applies to, and "at the rate" (gain × rate, before
  set-off, exemption, surcharge, cess and rebate);
- a sentence of basis, and notes for anything a CA should check.

Lines are grouped into buckets (section × side of 23 July 2024 × rate). Losses
offset gains inside a bucket and nowhere else; the 112A exemption is applied to
the 12.5% gains first, then the 10% ones. Cross-bucket and carried-forward
set-off is left to the CA.

Nothing is stored. The rules are keyed to the transfer date, so working a line
out again gives the same answer, and a 31 January 2018 value entered today
corrects every year's statement, not just this one. The older
`/tax/capital-gains` summary now takes short and long from this statement too,
instead of from the term stored on each disposal at its last rebuild.

## 2. The rules, and where they come from

Checked on 14 September 2026 against the e-filing portal's Schedule 112A CSV
instructions (static.incometax.gov.in), the CBDT Cost Inflation Index
notifications, and published summaries of the Finance (No. 2) Act, 2024 and the
Income-tax Act, 2025. incometaxindia.gov.in itself refused automated reads that
day, so the Act text was confirmed through secondary sources; re-check against
the portal when this file is next touched.

| Rule | Before 23 July 2024 | On or after 23 July 2024 |
|---|---|---|
| Long-term after (s. 2(42A)) | 12 months: listed securities other than units, equity-fund units; 24: unlisted shares, land or building; 36: everything else (incl. REIT/InvIT units, gold, debt funds) | 12 months: listed securities incl. units, equity-fund units; 24: everything else |
| Short-term, listed equity (s. 111A) | 15% | 20% |
| Long-term, listed equity (s. 112A) | 10% | 12.5% |
| 112A exemption, per year | ₹1,00,000 (FY 2018-19 to 2023-24) | ₹1,25,000 (FY 2024-25 onward, for the whole year) |
| Long-term, other (s. 112) | 20% with indexation; 10% without for listed bonds; listed securities the lower of the two | 12.5% without indexation |
| Land or building bought before 23 July 2024, resident individual or HUF | — | lower of 12.5% without indexation and 20% with it |
| Deemed short-term (s. 50AA) | debt-fund units bought on or after 1 April 2023 | also unlisted bonds and debentures |
| Virtual digital assets (s. 115BBH) | 30% | 30% |

**Grandfathering, s. 55(2)(ac).** For an equity share, equity-fund unit or
business-trust unit acquired on or before 31 January 2018 and sold long-term, the
cost is the higher of (a) what was paid and (b) the lower of the fair market
value on 31 January 2018 and the sale value. No offline source has that price,
so the owner enters it **once per holding**, per unit as the share stood that
day. Later splits are applied from the recorded split transactions, the same
way lot costs are adjusted. Until it is entered, the line uses what was paid —
which can only overstate the gain — and says so.

**Cost Inflation Index.** Base 2001-02 = 100, table to 2026-27 = 384, each year's
source in `CostInflationIndex.kt`: Notification No. 44/2017 (S.O. 1790(E)) and its
annual amendments, No. 44/2024 (363), No. 70/2025 (376), and No. 85/2026 under
section 72(8)(a) of the Income-tax Act, 2025 (384). A year not yet notified is
reported as missing, never guessed. Anything bought before 1 April 2001 is
indexed from the base year, with a note that the FMV on that date may be used.

**Income-tax Act, 2025.** From 1 April 2026 ("tax year 2026-27") the same rules
are sections 196 (111A), 197 (112), 198 (112A) and 76 (50AA). Budget 2026 left
the rates unchanged. Lines for those sales carry the new section in a note;
the section codes stay the 1961 ones, which every CA still reads.

**Classification from what is recorded.** Listed shares and IPO allotments are
listed equity; a fund is equity when its category is equity, ELSS or index, and
debt when it is debt or liquid — otherwise it is treated as a non-equity fund
and flagged. REIT/InvIT units are business-trust units. A bond with an ISIN is
treated as listed, without one as unlisted, and both are said. ESOP/RSU is
treated as unlisted, with a note that listed-company shares are listed and cost
is the perquisite value. Sovereign Gold Bonds note that redemption at maturity
by the original subscriber can be exempt.

**Assumed, and said on every statement:** STT paid on listed equity; a resident
individual or HUF for the land-or-building comparison; joint holdings shown in
full for each owner. **Not applied:** transfer expenses (Schedule 112A column 12
is zero), set-off across buckets or years, surcharge, cess, 87A rebate, basic
exemption, sections 54 to 54F.

## 3. Worked examples

Each is a test in `CapitalGainsRulesTest`; change one, change both.

**1. Grandfathered share.** 100 shares bought 10 June 2016 at ₹500 (₹50,000);
value on 31 January 2018 ₹1,200; sold 15 September 2024 at ₹1,500 (₹1,50,000).
FMV total ₹1,20,000; lower of that and ₹1,50,000 is ₹1,20,000; higher of that and
₹50,000 is ₹1,20,000. Gain **₹30,000**, 112A, on or after 23 July 2024, 12.5%.
- 1b. Sold at ₹900 instead: the lower of ₹1,20,000 and ₹90,000 is ₹90,000, so the
  cost is ₹90,000 and the gain is **nil** — grandfathering cannot create a loss.
- 1c. Bought at ₹1,400 (₹1,40,000): the actual cost is higher and stays; gain
  **₹10,000**.

**2. A split since 2018.** The same share split 2:1 in 2020, so 200 units are
sold. The ₹1,200 entered becomes ₹600 per today's unit; the result is Example 1.

**3. The day the rates changed.** Bought 1 May 2023, sold 22 July 2024: 112A at
**10%**. Sold 23 July 2024: **12.5%**. Bought 1 March 2024, sold 1 July 2024:
111A at **15%**; sold 1 August 2024: **20%**.

**4. An old flat.** Bought 15 May 2010 (FY 2010-11, index 167) for ₹30,00,000;
sold 10 June 2025 (FY 2025-26, index 376) for ₹90,00,000.
Without indexation: gain ₹60,00,000 × 12.5% = ₹7,50,000.
With it: cost ₹30,00,000 × 376 ÷ 167 = ₹67,54,491; gain ₹22,45,509 × 20% =
**₹4,49,102**, the lower, so 20% with indexation is used.
- 4b. Bought 1 June 2020 (index 301) for ₹80,00,000, sold for ₹90,00,000: indexed
  cost ₹99,93,355 makes a loss, so the tax at the rate is **nil** — and that
  indexed loss cannot be set off, which the line says.

**5. Gold.** Bought 1 May 2019 (index 289) for ₹1,00,000, sold 1 June 2024 (index
363) for ₹1,80,000: long-term, indexed cost ₹1,25,606, gain ₹54,394, at 20%
**₹10,879**.
- 5b. Bought 1 September 2022, sold 1 October 2024 (25 months): long-term under
  the 24-month rule, 12.5% on ₹30,000 = ₹3,750. Bought 1 June 2022, sold 1 July
  2024 (25 months, but before the change): short-term, slab rate.

**6. Debt funds.** Bought 1 May 2023, sold 1 June 2026: **deemed short-term**
(50AA), slab rate. Bought 1 January 2022, sold 1 September 2024: long-term, 12.5%.

**7. The exemption, FY 2024-25.** ₹80,000 of 112A gains before 23 July 2024 and
₹1,00,000 after. The ₹1,25,000 exemption covers the ₹1,00,000 at 12.5% first,
then ₹25,000 of the rest: at the rate, ₹0 and (₹80,000 − ₹25,000) × 10% =
**₹5,500**.

## 4. The files

**Schedule 112A CSV** (`GET …/tax/schedule-112a?fy=&member=`). The long-term
listed-equity lines in the schedule's columns, as the portal's CSV instructions
define them: 1a BE/AE for acquisition on or before / after 31 January 2018; 1b
BE/AE for transfer before / on or after 23 July 2024; ISIN or `INNOTAVAILAB`;
name reduced to letters, digits and spaces; 6 = 4 × 5, 9 = lower of 11 and 6,
7 = higher of 8 and 9, 11 = 4 × 10, 13 = 7 + 12, 14 = 6 − 13, rounded to the
rupee. BE lots are one row each; AE acquisitions are one `CONSOLIDATED` row per
side of 23 July 2024, as the schedule asks. The header row is plain column
descriptions: the portal's own template header must not be altered, so this
file is for reading and pasting rows from, and does not claim to be the upload.

**The tax-pack PDF** (`GET …/tax/pack/pdf?fy=&member=`). A one-page cover —
whose pack, the year, the date it was prepared, the notice, the net gain in brass
with the amount in words, the buckets, deductions, interest, and what is worth a
look — then the lines lot by lot, the Schedule 112A rows, deductions and
interest in full, the 31 January 2018 values with their sources, the
assumptions, and the index table. Fraunces and Inter are embedded (SIL OFL);
nothing is set below 10pt. Both downloads are audited (`tax.pack.pdf`,
`tax.schedule_112a.csv`).

## 5. Share with my CA

The existing guest link (docs/05 §7) with scope `tax_pack`, now able to name the
taxpayer (`memberId`, stored as `guest_shares.scope_member_id`). The scope is
still materialised when the link is made, under the sharer's own row-level
security; it now includes holdings sold outright during the year, which have no
unrealised position left but are the reason the CA was sent the link.

Creating one returns `downloadUrl`, once:
`/api/v1/share/{token}/tax-pack.pdf`, beside `/schedule-112a.csv`. Each is opened
exactly like the link — same token check, same view count and `max_views`, same
audit, same clamped read-only guest session — and rendered from that session's
payload, so a file cannot hold more than the link does. Any other kind of link
has no such file, and answers "not found". The web screen gives the PDF link as
the thing to send, because `/share/{token}` still has no page of its own (known
issue 43, "A guest link has no page to open").

## 6. The 31 January 2018 value

`PUT …/tax/grandfathering/{investmentId}` with `{ fmvPerUnit, sourceNote }`;
`DELETE` to clear. Only for listed shares, equity funds and REIT/InvIT units
(`fmv_not_applicable` otherwise); non-negative, at most four decimals. Stored in
`investment_fmv_2018`, one row per holding (lots are rebuilt from transactions
and would lose anything attached to them), with row-level security cascading
from the investment: a value on a holding you cannot see does not exist for you,
a holding you can see but not change answers 404, and a guest session can read
the value on a linked holding and write nothing. Audited as `tax.fmv_2018.set`
and `.clear`. The schedule lists every visible holding with a lot on or before
31 January 2018 — sold or not — so the owner can work through them once.

## 7. Not built

- **AIS / Form 26AS reconciliation.** It needs the taxpayer's own statements,
  downloaded from the portal with their credentials; there is nothing to test
  against without real taxpayer data. Known issue 45, "AIS and Form 26AS reconciliation is not built".
- Transfer expenses, set-off, surcharge, cess, rebate and section 54-54F
  exemptions — the CA's working, deliberately.
- Telugu and Devanagari in the PDF (known issue 44, "The CA pack cannot print Telugu or Devanagari").
