# -*- coding: utf-8 -*-
"""Generates db/migrations/V6__seed_taxonomy.sql from a declarative spec.

ONLY V6 — the initial seed. Once a migration is applied it is history, so later
changes to the taxonomy are their own migrations (see V15). Regenerating this
file is for reconstructing V6, not for evolving the taxonomy.

Keeping the taxonomy as data (not code) is what lets a custom type be promoted
into the official list with zero migration -- docs/01 section 5.
"""
import json

CATEGORIES = [
    # code,          label,                         icon,          color,     sort
    ("gold",         "Gold & Metals",               "coins",       "#C9A227", 10),
    ("deposits",     "Bank Deposits",               "landmark",    "#4E7C59", 20),
    ("mutual_funds", "Mutual Funds",                "pie-chart",   "#3E6B99", 30),
    ("equity",       "Equity",                      "trending-up", "#6A5A99", 40),
    ("ipo",          "IPO",                         "rocket",      "#A6555A", 50),
    ("bonds",        "Bonds",                       "scroll-text", "#7A6A55", 60),
    ("retirement",   "Retirement & Small Savings",  "piggy-bank",  "#3F8A8A", 70),
    ("insurance",    "Insurance",                   "shield",      "#2F7F76", 80),
    ("real_estate",  "Real Estate",                 "home",        "#B06B3A", 90),
    ("alternatives", "Alternatives",                "sparkles",    "#8A7F6A", 100),
    ("cash",         "Cash & Misc",                 "wallet",      "#7C8A6A", 110),
    ("universal",    "Anything Else",               "box",         "#6B6558", 120),
]


def f(key, label, dtype, group="more", sort=100, required=False,
      unit=None, options=None, help=None, placeholder=None):
    d = {"key": key, "label": label, "dataType": dtype,
         "group": group, "sort": sort, "required": required}
    if unit:        d["unit"] = unit
    if options:     d["options"] = [{"value": v, "label": l} for v, l in options]
    if help:        d["help"] = help
    if placeholder: d["placeholder"] = placeholder
    return d


def c(label, group="more", sort=100, required=False, unit=None, help=None):
    """Re-label a first-class column for this type (Principal vs Amount, etc.)."""
    d = {"label": label, "group": group, "sort": sort, "required": required}
    if unit: d["unit"] = unit
    if help: d["help"] = help
    return d


PURITY = [("24k", "24K (999)"), ("22k", "22K (916)"), ("18k", "18K (750)"), ("14k", "14K (585)")]
PAYOUT = [("cumulative", "Cumulative (at maturity)"), ("monthly", "Monthly"),
          ("quarterly", "Quarterly"), ("half_yearly", "Half-yearly"), ("yearly", "Yearly")]
PREMIUM_FREQ = [("monthly", "Monthly"), ("quarterly", "Quarterly"),
                ("half_yearly", "Half-yearly"), ("yearly", "Yearly"), ("single", "Single premium")]

# cat, code, label, icon, sort, common-overrides, type-specific fields
TYPES = [
 # ---- Gold & metals -------------------------------------------------------
 ("gold", "gold_physical", "Physical Gold", "coins", 10,
  {"invested_amount": c("Amount paid", "essential", 20, True),
   "quantity": c("Weight", "essential", 30, True, unit="g"),
   "storage_location": c("Where it's kept", "essential", 40,
                         help="Home locker, bank locker, with a relative…")},
  [f("purity", "Purity", "select", "essential", 35, options=PURITY),
   f("making_charges", "Making charges", "money", sort=50),
   f("hallmark_no", "Hallmark / BIS number", "text", sort=60),
   f("bought_from", "Bought from", "text", sort=70)]),

 ("gold", "gold_jewelry", "Jewellery", "gem", 20,
  {"invested_amount": c("Amount paid", "essential", 20, True),
   "quantity": c("Gross weight", "essential", 30, True, unit="g"),
   "storage_location": c("Where it's kept", "essential", 40)},
  [f("purity", "Purity", "select", "essential", 35, options=PURITY),
   f("net_weight_g", "Net gold weight", "number", sort=45, unit="g",
     help="Excluding stones — this is what a valuer will weigh."),
   f("stone_weight_g", "Stone weight", "number", sort=46, unit="g"),
   f("making_charges", "Making charges", "money", sort=50),
   f("item_description", "What it is", "text", sort=60, placeholder="Bangles, chain, ear studs…")]),

 ("gold", "gold_digital", "Digital Gold", "smartphone", 30,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "quantity": c("Weight", "essential", 30, unit="g")},
  [f("platform", "Platform", "text", "essential", 40, placeholder="MMTC-PAMP, SafeGold…"),
   f("vault_partner", "Vault partner", "text", sort=50)]),

 ("gold", "gold_sgb", "Sovereign Gold Bond", "badge-indian-rupee", 40,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "quantity": c("Units", "essential", 30, unit="g"),
   "maturity_date": c("Maturity", "essential", 45)},
  [f("series", "Series", "text", "essential", 40, placeholder="2023-24 Series III"),
   f("issue_price", "Issue price per gram", "money", sort=50),
   f("interest_rate", "Interest rate", "percent", sort=60, help="SGBs pay 2.5% p.a. on the issue price."),
   f("demat_held", "Held in demat", "bool", sort=70)]),

 ("gold", "silver", "Silver", "coins", 50,
  {"invested_amount": c("Amount paid", "essential", 20, True),
   "quantity": c("Weight", "essential", 30, unit="g"),
   "storage_location": c("Where it's kept", "essential", 40)},
  [f("form", "Form", "select", sort=50,
     options=[("coins", "Coins"), ("bars", "Bars"), ("utensils", "Utensils"), ("jewelry", "Jewellery")])]),

 # ---- Bank deposits -------------------------------------------------------
 ("deposits", "fd", "Fixed Deposit", "landmark", 10,
  {"invested_amount": c("Principal", "essential", 20, True),
   "start_date": c("Opened on", "essential", 40),
   "maturity_date": c("Matures on", "essential", 50, True)},
  [f("interest_rate", "Interest rate", "percent", "essential", 30, required=True, unit="% p.a."),
   f("payout", "Interest payout", "select", sort=60, options=PAYOUT),
   f("receipt_no", "Receipt / FD number", "text", sort=70),
   f("auto_renew", "Auto-renew on maturity", "bool", sort=80),
   f("nominee_registered", "Nominee registered with bank", "bool", sort=90),
   f("tds_deducted", "TDS being deducted", "bool", sort=100)]),

 ("deposits", "rd", "Recurring Deposit", "calendar-clock", 20,
  {"invested_amount": c("Total deposited so far", "essential", 30),
   "maturity_date": c("Matures on", "essential", 50, True)},
  [f("monthly_amount", "Monthly instalment", "money", "essential", 20, required=True),
   f("interest_rate", "Interest rate", "percent", "essential", 40, unit="% p.a."),
   f("deposit_day", "Deposit day of month", "number", sort=60),
   f("account_no", "Account number", "text", sort=70)]),

 ("deposits", "tax_saver_fd", "Tax-saver FD (80C)", "receipt-indian-rupee", 30,
  {"invested_amount": c("Principal", "essential", 20, True),
   "start_date": c("Opened on", "essential", 30),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("interest_rate", "Interest rate", "percent", "essential", 35, unit="% p.a."),
   f("lock_in_years", "Lock-in", "number", sort=50, unit="years", help="Tax-saver FDs are locked for 5 years."),
   f("claimed_80c", "Claimed under 80C", "bool", sort=60)]),

 # ---- Mutual funds --------------------------------------------------------
 ("mutual_funds", "mf_sip", "Mutual Fund — SIP", "repeat", 10,
  {"invested_amount": c("Invested so far", "essential", 40),
   "quantity": c("Units held", unit="units", sort=60),
   "start_date": c("First instalment", "essential", 50)},
  [f("scheme_name", "Scheme", "text", "essential", 10, required=True),
   f("sip_amount", "SIP amount", "money", "essential", 20, required=True),
   f("sip_day", "SIP date", "number", "essential", 30, help="Day of the month the SIP is debited."),
   f("folio_no", "Folio number", "text", sort=70),
   f("plan", "Plan", "select", sort=80, options=[("direct", "Direct"), ("regular", "Regular")]),
   f("option", "Option", "select", sort=85, options=[("growth", "Growth"), ("idcw", "IDCW / Dividend")]),
   f("scheme_category", "Category", "select", sort=90,
     options=[("equity", "Equity"), ("debt", "Debt"), ("hybrid", "Hybrid"),
              ("index", "Index / ETF"), ("elss", "ELSS (80C)"), ("liquid", "Liquid")]),
   f("nav_at_purchase", "Average NAV", "number", sort=100)]),

 ("mutual_funds", "mf_lumpsum", "Mutual Fund — Lumpsum", "pie-chart", 20,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "quantity": c("Units held", unit="units", sort=50),
   "start_date": c("Invested on", "essential", 30)},
  [f("scheme_name", "Scheme", "text", "essential", 10, required=True),
   f("folio_no", "Folio number", "text", "essential", 40),
   f("plan", "Plan", "select", sort=60, options=[("direct", "Direct"), ("regular", "Regular")]),
   f("scheme_category", "Category", "select", sort=70,
     options=[("equity", "Equity"), ("debt", "Debt"), ("hybrid", "Hybrid"),
              ("index", "Index / ETF"), ("elss", "ELSS (80C)"), ("liquid", "Liquid")]),
   f("nav_at_purchase", "NAV at purchase", "number", sort=80)]),

 # ---- Equity --------------------------------------------------------------
 ("equity", "stock_listed", "Listed Shares", "trending-up", 10,
  {"invested_amount": c("Amount invested", "essential", 40),
   "quantity": c("Quantity", "essential", 20, True, unit="shares"),
   "start_date": c("First bought", sort=60)},
  [f("symbol", "Symbol", "text", "essential", 10, required=True, placeholder="INFY, TCS…"),
   f("avg_cost", "Average cost", "money", "essential", 30),
   f("exchange", "Exchange", "select", "essential", 50,
     options=[("nse", "NSE"), ("bse", "BSE"), ("both", "Both")]),
   f("isin", "ISIN", "text", sort=70),
   f("demat_no", "Demat account", "text", sort=80)]),

 ("equity", "stock_unlisted", "Unlisted Shares", "file-lock", 20,
  {"invested_amount": c("Amount invested", "essential", 30, True),
   "quantity": c("Quantity", "essential", 20, unit="shares"),
   "storage_location": c("Where the certificate is", "essential", 50)},
  [f("company_name", "Company", "text", "essential", 10, required=True),
   f("broker", "Bought through", "text", "essential", 40),
   f("certificate_no", "Certificate number", "text", sort=60),
   f("cin", "Company CIN", "text", sort=70),
   f("held_in", "Held as", "select", sort=80,
     options=[("demat", "Demat"), ("physical", "Physical certificate")])]),

 ("equity", "esop_rsu", "ESOP / RSU", "award", 30,
  {"invested_amount": c("Exercise cost", sort=40),
   "quantity": c("Units granted", "essential", 20, True, unit="units")},
  [f("company_name", "Company", "text", "essential", 10, required=True),
   f("grant_date", "Grant date", "date", "essential", 30),
   f("vesting_schedule", "Vesting", "text", "essential", 35, placeholder="25% a year over 4 years"),
   f("vested_qty", "Vested so far", "number", sort=45, unit="units"),
   f("strike_price", "Strike price", "money", sort=50),
   f("cliff_date", "Cliff date", "date", sort=60)]),

 # ---- IPO -----------------------------------------------------------------
 ("ipo", "ipo_application", "IPO Application", "rocket", 10,
  {"invested_amount": c("Amount blocked", "essential", 30),
   "quantity": c("Lots applied", "essential", 20, unit="lots")},
  [f("company_name", "Company", "text", "essential", 10, required=True),
   f("application_no", "Application / UPI ref", "text", "essential", 40),
   f("allotted_qty", "Allotted quantity", "number", sort=50, unit="shares"),
   f("listing_date", "Listing date", "date", sort=60),
   f("category", "Applied as", "select", sort=70,
     options=[("retail", "Retail"), ("hni", "HNI"), ("employee", "Employee"), ("shareholder", "Shareholder")])]),

 # ---- Bonds ---------------------------------------------------------------
 ("bonds", "bond", "Bond / NCD", "scroll-text", 10,
  {"invested_amount": c("Amount invested", "essential", 30, True),
   "quantity": c("Units", sort=50, unit="units"),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("issuer", "Issuer", "text", "essential", 10, required=True),
   f("coupon_rate", "Coupon", "percent", "essential", 20, unit="% p.a."),
   f("face_value", "Face value", "money", sort=60),
   f("isin", "ISIN", "text", sort=70),
   f("bond_kind", "Kind", "select", sort=80,
     options=[("govt", "Government"), ("corporate", "Corporate"),
              ("ncd", "NCD"), ("tbill", "Treasury bill"), ("psu", "PSU")]),
   f("payout", "Interest payout", "select", sort=90, options=PAYOUT)]),

 # ---- Retirement & small savings -----------------------------------------
 ("retirement", "ppf", "PPF", "piggy-bank", 10,
  {"invested_amount": c("Balance / contributed", "essential", 30),
   "start_date": c("Opened on", "essential", 40),
   "maturity_date": c("Matures on", "essential", 50)},
  [f("account_no", "Account number", "text", "essential", 20),
   f("yearly_contribution", "Yearly contribution", "money", sort=60),
   f("claimed_80c", "Claimed under 80C", "bool", sort=70)]),

 ("retirement", "epf", "EPF", "building-2", 20,
  {"invested_amount": c("Balance", "essential", 30)},
  [f("uan", "UAN", "text", "essential", 10),
   f("member_id", "PF member ID", "text", "essential", 20),
   f("employer", "Employer", "text", sort=40),
   f("employee_share", "Employee share", "money", sort=50),
   f("employer_share", "Employer share", "money", sort=60),
   f("pension_share", "Pension (EPS) share", "money", sort=70)]),

 ("retirement", "nps", "NPS", "shield-check", 30,
  {"invested_amount": c("Total contributed", "essential", 30)},
  [f("pran", "PRAN", "text", "essential", 10, required=True),
   f("tier", "Tier", "select", "essential", 20, options=[("1", "Tier I"), ("2", "Tier II")]),
   f("scheme_preference", "Scheme", "select", sort=40,
     options=[("auto", "Auto choice"), ("active", "Active choice")]),
   f("claimed_80ccd1b", "Claimed under 80CCD(1B)", "bool", sort=50,
     help="The extra ₹50,000 deduction over and above 80C.")]),

 ("retirement", "ssy", "Sukanya Samriddhi (SSY)", "heart-handshake", 40,
  {"invested_amount": c("Balance / contributed", "essential", 30),
   "start_date": c("Opened on", "essential", 40),
   "maturity_date": c("Matures on", sort=60)},
  [f("account_no", "Account number", "text", "essential", 20),
   f("yearly_contribution", "Yearly contribution", "money", sort=50),
   f("claimed_80c", "Claimed under 80C", "bool", sort=70)]),

 ("retirement", "nsc_kvp", "NSC / KVP", "file-badge", 50,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "start_date": c("Bought on", "essential", 30),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("certificate_no", "Certificate number", "text", "essential", 10),
   f("scheme", "Scheme", "select", sort=50, options=[("nsc", "NSC"), ("kvp", "KVP")]),
   f("interest_rate", "Interest rate", "percent", sort=60, unit="% p.a.")]),

 ("retirement", "scss", "Senior Citizens Savings", "user-round", 60,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "start_date": c("Opened on", "essential", 30),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("account_no", "Account number", "text", "essential", 10),
   f("interest_rate", "Interest rate", "percent", sort=50, unit="% p.a."),
   f("payout", "Interest payout", "select", sort=60, options=PAYOUT)]),

 # ---- Insurance -----------------------------------------------------------
 ("insurance", "insurance_term", "Term Life", "shield", 10,
  {"invested_amount": c("Premium paid to date", sort=60),
   "start_date": c("Started on", sort=50),
   "maturity_date": c("Cover until", "essential", 40)},
  [f("policy_no", "Policy number", "text", "essential", 10, required=True),
   f("sum_assured", "Sum assured", "money", "essential", 20, required=True),
   f("premium_amount", "Premium", "money", "essential", 30, required=True),
   f("premium_due_date", "Next premium due", "date", "essential", 35),
   f("premium_frequency", "Premium frequency", "select", sort=45, options=PREMIUM_FREQ),
   f("agent_name", "Agent", "text", sort=70),
   f("agent_phone", "Agent phone", "text", sort=80)]),

 ("insurance", "insurance_endowment", "Endowment / Money-back", "shield-half", 20,
  {"invested_amount": c("Premium paid to date", sort=60),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("policy_no", "Policy number", "text", "essential", 10, required=True),
   f("sum_assured", "Sum assured", "money", "essential", 20),
   f("premium_amount", "Premium", "money", "essential", 30),
   f("premium_due_date", "Next premium due", "date", "essential", 35),
   f("premium_frequency", "Premium frequency", "select", sort=45, options=PREMIUM_FREQ),
   f("bonus_accrued", "Bonus accrued", "money", sort=50),
   f("agent_name", "Agent", "text", sort=70)]),

 ("insurance", "insurance_ulip", "ULIP", "layers", 30,
  {"invested_amount": c("Premium paid to date", sort=50),
   "quantity": c("Units", sort=60, unit="units"),
   "maturity_date": c("Matures on", "essential", 40)},
  [f("policy_no", "Policy number", "text", "essential", 10, required=True),
   f("sum_assured", "Sum assured", "money", "essential", 20),
   f("premium_amount", "Premium", "money", "essential", 30),
   f("premium_due_date", "Next premium due", "date", "essential", 35),
   f("fund_name", "Fund", "text", sort=70),
   f("lock_in_end", "Lock-in ends", "date", sort=80)]),

 ("insurance", "insurance_health", "Health Insurance", "stethoscope", 40,
  {"invested_amount": c("Premium paid to date", sort=60)},
  [f("policy_no", "Policy number", "text", "essential", 10, required=True),
   f("cover_amount", "Cover", "money", "essential", 20, required=True),
   f("premium_amount", "Premium", "money", "essential", 30),
   f("renewal_date", "Renews on", "date", "essential", 40),
   f("members_covered", "Who's covered", "text", "essential", 50),
   f("claimed_80d", "Claimed under 80D", "bool", sort=70),
   f("tpa", "TPA / helpline", "text", sort=80),
   f("room_rent_limit", "Room rent limit", "text", sort=90)]),

 # ---- Real estate ---------------------------------------------------------
 ("real_estate", "property", "Property", "home", 10,
  {"invested_amount": c("Purchase price", "essential", 30, True),
   "start_date": c("Bought on", sort=60),
   "storage_location": c("Where the deed is", "essential", 50)},
  [f("address", "Address", "text", "essential", 10, required=True),
   f("property_kind", "Kind", "select", "essential", 20,
     options=[("land", "Land"), ("flat", "Flat / Apartment"), ("house", "Independent house"),
              ("commercial", "Commercial"), ("agricultural", "Agricultural")]),
   f("area", "Area", "number", "essential", 40, unit="sq ft"),
   f("survey_no", "Survey / plot number", "text", sort=70),
   f("registration_no", "Registration number", "text", sort=80),
   f("rent_received", "Monthly rent", "money", sort=90),
   f("current_valuation", "Latest valuation", "money", sort=100),
   f("co_owners", "Co-owners on the deed", "text", sort=110)]),

 ("real_estate", "reit", "REIT / InvIT", "building", 20,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "quantity": c("Units", "essential", 30, unit="units")},
  [f("scheme_name", "Name", "text", "essential", 10, required=True),
   f("exchange", "Exchange", "text", sort=40),
   f("isin", "ISIN", "text", sort=50)]),

 # ---- Alternatives --------------------------------------------------------
 ("alternatives", "chit_fund", "Chit Fund", "users", 10,
  {"invested_amount": c("Paid so far", "essential", 30),
   "start_date": c("Started", "essential", 50),
   "maturity_date": c("Ends", sort=60)},
  [f("operator", "Chit company / organiser", "text", "essential", 10, required=True),
   f("chit_value", "Chit value", "money", "essential", 20, required=True),
   f("monthly_instalment", "Monthly instalment", "money", "essential", 40),
   f("total_months", "Duration", "number", sort=70, unit="months"),
   f("prized", "Already prized / taken", "bool", sort=80),
   f("group_no", "Group / ticket number", "text", sort=90)]),

 ("alternatives", "crypto", "Crypto", "bitcoin", 20,
  {"invested_amount": c("Amount invested", "essential", 30, True),
   "quantity": c("Quantity", "essential", 20)},
  [f("asset_symbol", "Asset", "text", "essential", 10, required=True, placeholder="BTC, ETH…"),
   f("exchange_or_wallet", "Exchange / wallet", "text", "essential", 40),
   f("custody", "Custody", "select", sort=50,
     options=[("exchange", "On an exchange"), ("self", "Self-custody wallet"), ("hardware", "Hardware wallet")]),
   f("wallet_hint", "Where the keys are", "text", sort=60,
     help="A hint for your family — never the seed phrase itself.")]),

 ("alternatives", "p2p_lending", "P2P Lending", "handshake", 30,
  {"invested_amount": c("Amount lent", "essential", 20, True),
   "maturity_date": c("Expected return date", sort=50)},
  [f("platform", "Platform", "text", "essential", 10, required=True),
   f("expected_return", "Expected return", "percent", "essential", 30, unit="% p.a."),
   f("borrower_ref", "Reference", "text", sort=40)]),

 ("alternatives", "business_equity", "Business / Startup Stake", "briefcase", 40,
  {"invested_amount": c("Amount invested", "essential", 20, True),
   "start_date": c("Invested on", sort=50)},
  [f("business_name", "Business", "text", "essential", 10, required=True),
   f("stake_pct", "Stake", "percent", "essential", 30, unit="%"),
   f("agreement_location", "Where the agreement is", "text", "essential", 40),
   f("co_investors", "Co-investors", "text", sort=60)]),

 ("alternatives", "collectible", "Art / Collectible", "palette", 50,
  {"invested_amount": c("Amount paid", "essential", 20, True),
   "storage_location": c("Where it's kept", "essential", 30)},
  [f("item_description", "What it is", "text", "essential", 10, required=True),
   f("artist_or_maker", "Artist / maker", "text", sort=40),
   f("provenance", "Provenance / certificate", "text", sort=50),
   f("appraised_value", "Last appraised value", "money", sort=60)]),

 # ---- Cash & misc ---------------------------------------------------------
 ("cash", "savings_buffer", "Savings / Emergency Buffer", "wallet", 10,
  {"invested_amount": c("Amount", "essential", 20, True)},
  [f("purpose", "What it's for", "text", "essential", 30, placeholder="Emergency fund, school fees…")]),

 ("cash", "cash_on_hand", "Cash on Hand", "banknote", 20,
  {"invested_amount": c("Amount", "essential", 20, True),
   "storage_location": c("Where it's kept", "essential", 30)},
  []),

 ("cash", "loan_given", "Money Lent to Someone", "hand-coins", 30,
  {"invested_amount": c("Amount lent", "essential", 20, True),
   "start_date": c("Lent on", "essential", 40),
   "maturity_date": c("Expected back by", "essential", 50)},
  [f("borrower_name", "Lent to", "text", "essential", 10, required=True),
   f("borrower_relationship", "Relationship", "text", sort=30),
   f("interest_rate", "Interest", "percent", sort=60, unit="% p.a."),
   f("written_record", "Is there a written record?", "bool", sort=70),
   f("borrower_phone", "Phone", "text", sort=80)]),

 # ---- Universal -----------------------------------------------------------
 ("universal", "universal", "Anything Else", "box", 10,
  {"invested_amount": c("Value", "essential", 20,
                        help="What it is worth, or what you paid."),
   "quantity": c("Quantity", "essential", 30),
   "storage_location": c("Where it is / who holds it", "essential", 40),
   "maturity_date": c("Any important date", sort=50)},
  [f("what_it_is", "What is it?", "text", "essential", 10,
     placeholder="A stake in a friend's shop, farmland, a vintage watch…",
     help="Describe it in your own words. You can add your own fields below.")]),
]


def sql_str(s):
    return "'" + s.replace("'", "''") + "'"


out = []
out.append("""-- =============================================================================
-- V6 - Seed the asset taxonomy.
--
-- The taxonomy is DATA, not code. That is what makes the docs/01 section 5
-- promotion path real: a household's custom type becomes an official one by
-- setting household_id back to NULL. No migration, no data movement.
--
-- Generated from db/taxonomy.py - regenerate rather than hand-editing.
-- =============================================================================

insert into asset_categories (code, label, icon, color, sort) values""")
rows = []
for code, label, icon, color, sort in CATEGORIES:
    rows.append("  (%s, %s, %s, %s, %d)" % (sql_str(code), sql_str(label), sql_str(icon), sql_str(color), sort))
out.append(",\n".join(rows) + ";\n")

out.append("""-- Built-in types. household_id stays NULL: these are global.
--
-- field_schema drives BOTH the client form and server-side validation from one
-- definition, so a form can never ask for something the API will reject.
--   common[col]  relabels a first-class column for this type
--                (a Fixed Deposit's "Principal" is investments.invested_amount)
--   fields[]     type-specific values stored in investments.attributes
--   group        'essential' renders on the form; 'more' hides behind
--                "More details" -- docs/02 section 6.12 caps essentials at 5.
insert into investment_types (category_id, code, label, icon, sort, field_schema)
select c.id, t.code, t.label, t.icon, t.sort, t.field_schema::jsonb
from (values""")
trows = []
for cat, code, label, icon, sort, common, fields in TYPES:
    schema = {"common": common, "fields": sorted(fields, key=lambda x: x["sort"])}
    ess = [k for k, v in common.items() if v.get("group") == "essential"]
    ess += [x["key"] for x in fields if x["group"] == "essential"]
    if len(ess) > 6:
        raise SystemExit("type %s has %d essential fields (max 6): %s" % (code, len(ess), ess))
    js = json.dumps(schema, ensure_ascii=False, separators=(",", ":"))
    trows.append("  (%s, %s, %s, %s, %d, %s)"
                 % (sql_str(cat), sql_str(code), sql_str(label), sql_str(icon), sort, sql_str(js)))
out.append(",\n".join(trows))
out.append(""") as t(cat, code, label, icon, sort, field_schema)
join asset_categories c on c.code = t.cat;
""")

INSTITUTIONS = [
    ("bank", ["State Bank of India", "HDFC Bank", "ICICI Bank", "Axis Bank", "Kotak Mahindra Bank",
              "Punjab National Bank", "Bank of Baroda", "Canara Bank", "Union Bank of India",
              "IndusInd Bank", "IDFC First Bank", "Yes Bank", "Federal Bank", "Indian Bank",
              "Bank of India", "Central Bank of India", "Karnataka Bank", "South Indian Bank",
              "AU Small Finance Bank", "Bandhan Bank", "RBL Bank", "Andhra Pragathi Grameena Bank"]),
    ("amc", ["SBI Mutual Fund", "HDFC Mutual Fund", "ICICI Prudential Mutual Fund",
             "Nippon India Mutual Fund", "Axis Mutual Fund", "Kotak Mahindra Mutual Fund",
             "Aditya Birla Sun Life Mutual Fund", "UTI Mutual Fund", "Mirae Asset Mutual Fund",
             "DSP Mutual Fund", "Parag Parikh Mutual Fund", "Quant Mutual Fund",
             "Canara Robeco Mutual Fund", "Franklin Templeton", "Motilal Oswal Mutual Fund",
             "Tata Mutual Fund", "Edelweiss Mutual Fund", "Bandhan Mutual Fund"]),
    ("broker", ["Zerodha", "Groww", "Upstox", "Angel One", "ICICI Direct", "HDFC Securities",
                "Kotak Securities", "5paisa", "Sharekhan", "Motilal Oswal", "Dhan", "Fyers",
                "SBI Securities", "Axis Direct", "IIFL Securities"]),
    ("insurer", ["LIC of India", "HDFC Life", "ICICI Prudential Life", "SBI Life", "Max Life",
                 "Bajaj Allianz Life", "Tata AIA Life", "Kotak Life", "PNB MetLife",
                 "Star Health", "Niva Bupa", "Care Health", "HDFC ERGO", "ICICI Lombard",
                 "Bajaj Allianz General", "New India Assurance", "Aditya Birla Health"]),
    ("post_office", ["India Post"]),
    ("govt", ["Reserve Bank of India", "EPFO", "NSDL (NPS)", "CDSL", "NSDL"]),
    ("exchange", ["NSE", "BSE", "MCX", "CoinDCX", "WazirX", "Zebpay"]),
    ("other", ["MMTC-PAMP", "SafeGold", "Tanishq", "Kalyan Jewellers", "Malabar Gold & Diamonds",
               "Joyalukkas", "GRT Jewellers"]),
]
out.append("""
-- Global institution directory. Seeds the searchable, logo-led picker
-- (docs/02 section 6.3); a household can always add its own, so capture never
-- dead-ends on a missing name.
insert into institutions (household_id, name, kind) values""")
irows = []
for kind, names in INSTITUTIONS:
    for n in names:
        irows.append("  (null, %s, %s)" % (sql_str(n), sql_str(kind)))
out.append(",\n".join(irows) + ";")

sql = "\n".join(out) + "\n"
open("db/migrations/V6__seed_taxonomy.sql", "w").write(sql)
print("categories: %d" % len(CATEGORIES))
print("types:      %d" % len(TYPES))
print("institutions: %d" % len(irows))
print("bytes: %d" % len(sql))
