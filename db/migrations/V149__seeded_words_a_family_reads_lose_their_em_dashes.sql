-- =============================================================================
-- V149 · The seeded words a family reads lose their em dashes.
-- Refs: commits 029500e (the web client), 46a99af (the sentences the server
--       writes), reports/ExportService.kt and continuity/HandbookService.kt
--       (`ascii`), capture/QuickAddService.kt (`keywordsFor`)
--
-- Rule 1 is that no text a person can see contains an em dash. The web client
-- and the sentences the server writes were rewritten; the reference data seeded
-- into this database was not, and it is read on the same screens: the type
-- someone picks in capture ("Mutual Fund — SIP"), the help under a field, and
-- the "how your family claims this" prose, which is also printed in the family
-- handbook.
--
-- It is not only a house rule here. Both PDFs are drawn with the Standard-14
-- fonts, which encode WinAnsi, so `ascii()` replaces anything outside 32..255
-- with "?". Every em dash below has been printing as a question mark in an
-- exported statement and in the handbook a family reads at the worst moment
-- they will ever read anything.
--
-- V6, V15 and V19 have been applied everywhere, so they are history and are not
-- touched: this rewrites the rows they wrote. db/taxonomy.py is left alone for
-- the same reason: it reconstructs V6, it does not evolve the taxonomy (V15).
--
-- Every statement matches on the value V6, V15 or V19 wrote, so running it a
-- second time changes nothing, and a household's own custom type (household_id
-- is not null) is never touched: those words are theirs, not ours.
--
-- One em dash in V6 needs nothing done to it. The help on crypto's wallet_hint
-- ("A hint for your family — never the seed phrase itself.") went with the
-- field itself in V34, so no live database still holds it.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- The two type labels. Brackets rather than a colon, because the label is read
-- on its own in a picker and in a PDF column, where a trailing clause after a
-- colon reads as a sentence that lost its start.
--
-- QuickAddService.keywordsFor takes the words before the bracket as a keyword
-- of its own, which is what keeps "mutual fund" finding a SIP.
-- -----------------------------------------------------------------------------

update investment_types
   set label = 'Mutual Fund (SIP)'
 where household_id is null and code = 'mf_sip'
   and label = 'Mutual Fund — SIP';

update investment_types
   set label = 'Mutual Fund (Lumpsum)'
 where household_id is null and code = 'mf_lumpsum'
   and label = 'Mutual Fund — Lumpsum';

-- -----------------------------------------------------------------------------
-- The help under three fields. The sentence is rewritten inside field_schema as
-- text and parsed back: jsonb is already normalised in storage, so nothing else
-- in the schema moves, and schema_version is bumped exactly as V15 bumps it
-- when a schema changes under a client.
-- -----------------------------------------------------------------------------

update investment_types t
   set field_schema = replace(t.field_schema::text, v.original, v.rewritten)::jsonb,
       schema_version = t.schema_version + 1
  from (values
    -- V6 · gold_jewelry, "Net gold weight"
    ('gold_jewelry',
     'Excluding stones — this is what a valuer will weigh.',
     'Excluding stones. This is what a valuer will weigh.'),
    -- V15 · nps, "Yearly contribution"
    ('nps',
     'What goes in each year — used for the 80CCD(1B) meter.',
     'What goes in each year, used for the 80CCD(1B) meter.'),
    -- V15 · epf, "Yearly employee contribution"
    ('epf',
     'Your own share over a year — the employer''s share is not deductible.',
     'Your own share over a year. The employer''s share is not deductible.')
  ) as v(code, original, rewritten)
 where t.household_id is null
   and t.code = v.code
   and position(v.original in t.field_schema::text) > 0;

-- -----------------------------------------------------------------------------
-- The transmission playbooks (V19). Each row is named by its type code or, for
-- a whole-category playbook, by its category code: one of the two is always
-- set, and never both.
--
-- The wording does not change. A dash that hid a new sentence becomes a full
-- stop, a dash that introduced a list becomes a colon, and an aside between two
-- dashes goes into brackets.
-- -----------------------------------------------------------------------------

update transmission_playbooks p
   set summary = v.rewritten
  from (values
    ('insurance_term',
     'The nominee claims directly from the insurer. Do this first — it is usually the fastest money a family receives, and it does not wait for the will.',
     'The nominee claims directly from the insurer. Do this first. It is usually the fastest money a family receives, and it does not wait for the will.'),
    ('mutual_funds',
     'Units are transmitted to the nominee or the legal heir — they are not redeemed by the fund house. The registrar (CAMS or KFintech) handles the paperwork for most funds.',
     'Units are transmitted to the nominee or the legal heir. They are not redeemed by the fund house. The registrar (CAMS or KFintech) handles the paperwork for most funds.'),
    ('equity',
     'Shares move between demat accounts by a transmission request to the depository participant — the broker. The claimant needs a demat account of their own.',
     'Shares move between demat accounts by a transmission request to the depository participant (the broker). The claimant needs a demat account of their own.'),
    ('epf',
     'EPF is claimed through the employer or directly with the EPFO. Where a nomination is on file it is quick; where it is not, it is slow — worth checking the nomination while it can still be changed.',
     'EPF is claimed through the employer or directly with the EPFO. Where a nomination is on file it is quick; where it is not, it is slow. Worth checking the nomination while it can still be changed.'),
    ('nps',
     'The claim goes through the CRA — Protean or KFintech — via the point of presence where the account was opened.',
     'The claim goes through the CRA (Protean or KFintech) via the point of presence where the account was opened.'),
    ('real_estate',
     'Property does not pass by nomination. It passes under the will, or by succession where there is none, and then has to be mutated in the municipal records — which is the step families most often miss.',
     'Property does not pass by nomination. It passes under the will, or by succession where there is none, and then has to be mutated in the municipal records, which is the step families most often miss.'),
    ('gold',
     'There is no institution to claim from — which is exactly why it needs recording. What matters is that the family knows it exists and where it is.',
     'There is no institution to claim from, which is exactly why it needs recording. What matters is that the family knows it exists and where it is.'),
    ('gold_sgb',
     'SGBs are transmitted like any other government security, through the receiving office — the bank, post office or agent that issued them.',
     'SGBs are transmitted like any other government security, through the receiving office: the bank, post office or agent that issued them.')
  ) as v(playbook, original, rewritten)
 where coalesce(p.type_code, p.category_code) = v.playbook
   and p.summary = v.original;

update transmission_playbooks p
   set steps = replace(p.steps::text, v.original, v.rewritten)::jsonb
  from (values
    ('insurance_term',
     'Ask for the claim form number they want — LIC usually asks for Form 3783, and for Form 3801 where the policy is assigned.',
     'Ask for the claim form number they want. LIC usually asks for Form 3783, and for Form 3801 where the policy is assigned.'),
    ('insurance_endowment',
     'Say whether it is a death claim or a maturity claim — the forms differ.',
     'Say whether it is a death claim or a maturity claim: the forms differ.'),
    ('mutual_funds',
     'From the registrar — CAMS or KFintech — or the AMC website.',
     'From the registrar (CAMS or KFintech) or the AMC website.')
  ) as v(playbook, original, rewritten)
 where coalesce(p.type_code, p.category_code) = v.playbook
   and position(v.original in p.steps::text) > 0;

update transmission_playbooks
   set contact_hint = 'The registrar (CAMS or KFintech) or the fund house.'
 where category_code = 'mutual_funds'
   and contact_hint = 'The registrar — CAMS or KFintech — or the fund house.';

-- "Nobody administers this" is the answer to "who do we call?", so the colon
-- keeps the shape of an answer that then explains itself.
update transmission_playbooks p
   set authority = v.rewritten
  from (values
    ('gold', 'None — it is held, not administered', 'None: it is held, not administered'),
    ('alternatives', 'None — it is a private arrangement', 'None: it is a private arrangement')
  ) as v(playbook, original, rewritten)
 where coalesce(p.type_code, p.category_code) = v.playbook
   and p.authority = v.original;
