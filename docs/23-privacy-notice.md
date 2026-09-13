[‹ Index](README.md)

# 23 · Privacy notice

**Status: a draft. It has not had legal review.** It describes what the product
does today, in the words the app shows. It is not a privacy policy in the legal
sense yet, and it does not state a legal conclusion anywhere. Doc 09 lists a
privacy policy as a launch requirement; this is the text that review starts
from.

**Where the product shows it.** The web client opens it as a sheet from
**Settings → Privacy notice** and from the bottom of **onboarding** ("Read the
privacy notice"), in English, Telugu and Hindi (`privacy.*` in
`static/app/i18n.js`, rendered by `static/app/privacy.js`). The native app has
no settings or onboarding screen to put it on yet; it shows the key-holder
guidance itself under the sealed field (`WhereAndWhoWording`). When the notice
below changes, change the `privacy.*` strings with it: `scripts/check-spec.py`
checks that the key-holder paragraph and its guidance exist in all three
languages and that both entry points link to it.

---

## The notice, as shown

### What Almira stores

What you record: holdings, loans, accounts, the documents you upload, wills and
paperwork, the people in your household and your contacts. Your phone number or
email address, to sign you in. A log of changes, so a household can see who
changed what.

### What we can read, and what we can't

Most of what you record is stored so that our server can read it. That is how
totals, reports and search work, and it is shown only to the people you share it
with. Anything you seal with your passphrase is encrypted on your device before
it is sent. Almira cannot read it, and cannot get it back for you if the
passphrase is lost.

### Where the original is, and who holds the key

On a record you can write where the original is, and who holds the key or the
papers. That second line names another person, someone who may never use Almira
and has not agreed to be written down.

Both lines are sealed with your passphrase on your device, so they are
end-to-end encrypted. Almira cannot read them, search them or print them, and we
never contact the person named.

Please write a role or a relationship — “Amma”, “the CA”, “my brother” — rather
than a full name, an address or a phone number.

Whether recording another person this way needs their consent is with our
lawyers. We have not reached a conclusion, and this notice will change when
there is one.

### What we never do

Almira never moves money, never holds funds and never asks for a bank password.

---

## Why the key-holder paragraph says what it says

The owner's decision, for this run: *"Key-holder consent: I'm not giving you a
legal answer. Design mitigation while you get one — the field is end-to-end
encrypted so we cannot read it, and the UI should guide people toward 'Amma' or
'the CA' rather than full names and addresses."*

So, while legal review of third-party consent is **pending**:

- **What is stored.** Two sealed values per record at most, `original_location`
  and `key_holder` ([Doc 20](20-where-and-who.md) §2). The server holds
  ciphertext, row presence, a timestamp and who sealed it (Doc 20 §6). There is
  no plaintext copy anywhere: the older plaintext columns were retired in V33
  (Doc 20 §1), and the server refuses a request that still sends them.
- **That it names another person.** The notice says so plainly, rather than
  treating the key holder as the user's own data.
- **That it is sealed.** True of the database, not only of the client: nothing
  on the server can read, search or print either line, including the family
  handbook and its PDF.
- **The guidance.** A role or a relationship is enough for a family to act on
  ("the key is with Amma") and identifies the person less than a full name, an
  address or a phone number would. The same sentence is the helper text under
  the key-holder field in the web client (`where.keyHolderHelp`, three
  languages) and in the native app's sealed-field screen. The location line gets
  its own guidance (`where.locationHelp`): enough for the family to find it, no
  street address or locker number.
- **Pending, without a conclusion.** Doc 20 §4 records the open question
  (whether the DPDP Act's personal or domestic purpose exemption covers this).
  Nothing in the product or in this notice answers it.

## Not verified

- The notice has not been read by counsel. Nor have the Telugu and Hindi
  translations been checked by a native speaker.
- Seen in a browser against a local development server (V33's change): the link
  at the end of onboarding opened the notice in Telugu, and Settings → Privacy
  notice opened it in English, with every section and the draft banner. Hindi
  was rendered for the editor's helper text, not for the notice. Not seen at
  phone width.
- The native app has no screen for the notice itself.

[‹ Index](README.md)
