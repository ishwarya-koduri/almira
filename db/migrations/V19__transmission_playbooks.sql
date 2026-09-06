-- =============================================================================
-- V19 · The Transmission Assistant: how a family actually claims each thing.
-- Refs: docs/01 §10, docs/03 §8, docs/10 Phase 3
--
-- This is the part of the product that earns the name. A family that knows a
-- policy exists still has to find out that LIC wants Form 3783 and the original
-- policy document, that EPF goes through the employer or the EPFO portal, that
-- shares move by a CDSL/NSDL transmission request and not by "closing the
-- account", and that a flat needs mutation at the municipal office after the
-- succession certificate. Every one of those is learnable in an afternoon and
-- unbearable to learn in the week after a funeral.
--
-- The playbooks are reference data, seeded here and read by everyone — they
-- contain no household's information, so they carry no privacy of their own.
-- They are deliberately procedural and never legal advice: each says what a
-- family usually has to do and tells them to confirm it with the institution,
-- because forms change and this file does not.
-- =============================================================================

create table transmission_playbooks (
  id             uuid primary key default gen_random_uuid(),
  -- Matched most-specific-first: a type code wins over its category.
  type_code      text,
  category_code  text,
  title          text not null,
  summary        text not null,
  -- [{ "step": "...", "detail": "..." }]
  steps          jsonb not null default '[]',
  -- What the family will be asked for. Keys the app can tick off against what
  -- the household has already recorded.
  documents      jsonb not null default '[]',
  -- Who to call first, in words — "the branch that holds the FD".
  contact_hint   text,
  authority      text,
  typical_days   int,
  created_at     timestamptz not null default now(),
  constraint playbook_targets_something
    check (type_code is not null or category_code is not null)
);
create unique index on transmission_playbooks (coalesce(type_code, ''), coalesce(category_code, ''));

alter table transmission_playbooks enable row level security;
create policy playbooks_read on transmission_playbooks for select using (true);

insert into transmission_playbooks
  (type_code, category_code, title, summary, steps, documents, contact_hint, authority, typical_days)
values
('insurance_term', null,
 'Claiming a life insurance policy',
 'The nominee claims directly from the insurer. Do this first — it is usually the fastest money a family receives, and it does not wait for the will.',
 '[{"step":"Tell the insurer","detail":"Call the branch or the agent and say you are making a death claim. Ask for the claim form number they want — LIC usually asks for Form 3783, and for Form 3801 where the policy is assigned."},
   {"step":"Send the claim form with the originals","detail":"The original policy document, the death certificate, and the nominee''s KYC. Keep photocopies of everything you post."},
   {"step":"Bank details for the payout","detail":"A cancelled cheque or a passbook page in the nominee''s name, with an NEFT mandate."},
   {"step":"If the death was within three years of the policy","detail":"Expect an early-claim enquiry. It is routine; answer it fully and keep copies."}]',
 '["Original policy document","Death certificate","Nominee KYC (PAN and Aadhaar)","Cancelled cheque","Claim form from the insurer"]',
 'The agent who sold it, or the servicing branch printed on the policy.',
 'The insurance company', 30),

('insurance_endowment', null,
 'Claiming an endowment or money-back policy',
 'Same route as a term policy: the nominee claims from the insurer, and the bonus accrued to date is paid with the sum assured.',
 '[{"step":"Ask the insurer for the claim form","detail":"Say whether it is a death claim or a maturity claim — the forms differ."},
   {"step":"Send the originals","detail":"Policy document, death certificate, nominee KYC and bank details."},
   {"step":"Ask for the bonus statement","detail":"So the family can check the payout against what the policy had accrued."}]',
 '["Original policy document","Death certificate","Nominee KYC","Cancelled cheque"]',
 'The servicing branch printed on the policy.',
 'The insurance company', 30),

(null, 'deposits',
 'Claiming a bank deposit',
 'With a registered nominee the bank pays the nominee on production of the death certificate. Without one, the branch will ask for succession papers, which is slower and why registering a nominee matters.',
 '[{"step":"Visit the branch that holds the deposit","detail":"Take the deposit receipt or account number, the death certificate, and your own KYC."},
   {"step":"Fill the bank''s claim form","detail":"Every bank has its own; ask for the deceased-claim form."},
   {"step":"If no nominee is registered","detail":"For small balances most banks accept an indemnity and a legal-heir declaration; above their threshold they will ask for a succession certificate."},
   {"step":"Ask about interest to date","detail":"Interest accrues until the claim is settled and should be included."}]',
 '["Death certificate","Deposit receipt or account number","Claimant KYC","Bank claim form","Succession certificate (only if no nominee)"]',
 'The branch that holds the deposit.',
 'The bank', 21),

(null, 'mutual_funds',
 'Transmitting mutual fund units',
 'Units are transmitted to the nominee or the legal heir — they are not redeemed by the fund house. The registrar (CAMS or KFintech) handles the paperwork for most funds.',
 '[{"step":"Get the transmission form","detail":"From the registrar — CAMS or KFintech — or the AMC website. There are different forms for a nominee and for a legal heir."},
   {"step":"Submit with the death certificate","detail":"Attested copy, plus the claimant''s KYC and a cancelled cheque for the new folio."},
   {"step":"Above the registrar''s threshold","detail":"They may ask for an indemnity bond and, without a nominee, a succession certificate."},
   {"step":"Decide after the units arrive","detail":"Transmission moves the units. Whether to hold or redeem is a separate decision, taken calmly."}]',
 '["Death certificate","Transmission form","Claimant KYC","Cancelled cheque","Folio numbers"]',
 'The registrar — CAMS or KFintech — or the fund house.',
 'The AMC and its registrar', 30),

(null, 'equity',
 'Transmitting shares held in demat',
 'Shares move between demat accounts by a transmission request to the depository participant — the broker. The claimant needs a demat account of their own.',
 '[{"step":"Open a demat account if there isn''t one","detail":"The shares have to land somewhere."},
   {"step":"File a transmission request with the broker","detail":"Form TRF along with the death certificate and both client master reports."},
   {"step":"For a joint account","detail":"A deletion-of-name request moves the holding to the surviving holder, which is much simpler."},
   {"step":"Physical certificates","detail":"If any shares are still in paper, the company''s registrar handles it and will ask for dematerialisation first."}]',
 '["Death certificate","Transmission form (TRF)","Client master report of both accounts","Claimant KYC","Succession certificate (above the depository threshold, without a nominee)"]',
 'The broker who holds the demat account.',
 'CDSL or NSDL, through the broker', 30),

('epf', null,
 'Claiming EPF',
 'EPF is claimed through the employer or directly with the EPFO. Where a nomination is on file it is quick; where it is not, it is slow — worth checking the nomination while it can still be changed.',
 '[{"step":"Ask the employer''s HR for the claim forms","detail":"Form 20 for the accumulation, Form 10D for the pension, Form 5IF for the EDLI insurance."},
   {"step":"If the employer is gone","detail":"File directly with the EPFO office holding the account; the UAN helps them find it."},
   {"step":"Attach the death certificate and bank details","detail":"The payout goes to the nominee''s account by NEFT."}]',
 '["Death certificate","UAN or PF number","Claim forms 20, 10D and 5IF","Nominee KYC","Cancelled cheque"]',
 'The employer''s HR, or the EPFO office holding the account.',
 'EPFO', 45),

('ppf', null,
 'Claiming a PPF account',
 'The nominee or legal heir claims at the bank or post office holding the account. The balance is paid out; the account is not continued.',
 '[{"step":"Go to the branch or post office holding the account","detail":"Take the passbook, the death certificate and your KYC."},
   {"step":"Fill Form G","detail":"The claim form for a PPF account."},
   {"step":"Without a nomination","detail":"Up to the prescribed limit a legal-heir declaration is accepted; above it they will ask for succession papers."}]',
 '["Passbook","Death certificate","Form G","Claimant KYC"]',
 'The bank branch or post office holding the account.',
 'The bank or India Post', 30),

('nps', null,
 'Claiming NPS',
 'The claim goes through the CRA — Protean or KFintech — via the point of presence where the account was opened.',
 '[{"step":"Get the withdrawal form from the point of presence","detail":"Form 303 for a death claim under the All Citizens model."},
   {"step":"Attach the PRAN card and death certificate","detail":"Plus the nominee''s KYC and bank details."},
   {"step":"Ask about the annuity","detail":"Part of the corpus may have to buy an annuity; the CRA will explain what applies."}]',
 '["PRAN","Death certificate","Withdrawal form","Nominee KYC","Cancelled cheque"]',
 'The point of presence where the account was opened.',
 'PFRDA, through the CRA', 45),

('ssy', null,
 'Sukanya Samriddhi after the guardian',
 'The account belongs to the girl. If the guardian dies, another guardian takes over; the account continues.',
 '[{"step":"Tell the post office or bank","detail":"Take the passbook, the death certificate and the new guardian''s KYC."},
   {"step":"Submit the change-of-guardian form","detail":"The account continues in the girl''s name."}]',
 '["Passbook","Death certificate","New guardian KYC"]',
 'The post office or bank holding the account.',
 'India Post or the bank', 21),

(null, 'real_estate',
 'Transferring property',
 'Property does not pass by nomination. It passes under the will, or by succession where there is none, and then has to be mutated in the municipal records — which is the step families most often miss.',
 '[{"step":"Establish the title","detail":"Probate or a succession certificate, depending on the state and whether there is a will."},
   {"step":"Apply for mutation","detail":"At the municipal or panchayat office, so the tax records name the new owner. Until this is done the property is still in the deceased''s name for every practical purpose."},
   {"step":"Update the society and the utilities","detail":"Share certificate, maintenance, electricity and water."},
   {"step":"If there is a loan against it","detail":"Tell the lender. The EMI does not pause, and many home loans carry insurance that may settle the balance."}]',
 '["Death certificate","Will or succession certificate","Sale deed and previous title documents","Property tax receipts","Society share certificate"]',
 'The lawyer who handled the purchase, and the municipal office.',
 'The state revenue and municipal authorities', 120),

(null, 'gold',
 'Physical gold and jewellery',
 'There is no institution to claim from — which is exactly why it needs recording. What matters is that the family knows it exists and where it is.',
 '[{"step":"Retrieve it from where it is kept","detail":"A bank locker needs the death certificate and, unless there is a survivor or a nominee on the locker, an inventory taken in the bank''s presence."},
   {"step":"Check the purchase invoices","detail":"They establish cost for capital gains if it is ever sold."},
   {"step":"Divide it as the will says","detail":"Jewellery is where families disagree most; written instructions are worth more here than anywhere else."}]',
 '["Death certificate","Locker agreement","Purchase invoices","Will"]',
 'The bank branch holding the locker.',
 'None — it is held, not administered', 14),

('gold_sgb', null,
 'Sovereign Gold Bonds',
 'SGBs are transmitted like any other government security, through the receiving office — the bank, post office or agent that issued them.',
 '[{"step":"Approach the receiving office","detail":"With the holding certificate, the death certificate and the claimant''s KYC."},
   {"step":"Ask about the redemption date","detail":"The bond continues to its maturity in the claimant''s name unless redeemed at a window."}]',
 '["Holding certificate","Death certificate","Claimant KYC"]',
 'The bank or post office that issued the bond.',
 'RBI, through the receiving office', 30),

(null, 'alternatives',
 'Chits, private lending and everything unusual',
 'These have no standard route, which is precisely why they get lost. The record and the operator''s phone number are most of the value here.',
 '[{"step":"Contact the operator directly","detail":"The chit foreman, the borrower, the exchange. Take the death certificate and whatever agreement exists."},
   {"step":"Find the agreement","detail":"A chit passbook, a signed loan note, a partnership deed. Without it the family is relying on goodwill."},
   {"step":"Write down what was agreed","detail":"If the arrangement was informal, record it now while someone still remembers it."}]',
 '["Death certificate","The agreement, passbook or note","Claimant KYC"]',
 'The operator or counterparty named on the record.',
 'None — it is a private arrangement', 60),

(null, 'retirement',
 'Small savings and post office schemes',
 'NSC, KVP, SCSS and the rest are claimed at the post office or bank holding them, on a nomination or a legal-heir basis.',
 '[{"step":"Take the certificate and the death certificate","detail":"To the post office or branch that issued it."},
   {"step":"Fill the claim form","detail":"The counter will give you the right one for the scheme."},
   {"step":"Ask whether the scheme can continue","detail":"Some can be transferred rather than closed, which may be worth more."}]',
 '["The certificate or passbook","Death certificate","Claimant KYC","Claim form"]',
 'The post office or bank that issued it.',
 'India Post or the bank', 30);
