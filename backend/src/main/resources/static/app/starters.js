/* =============================================================================
   Starting points (X-31, docs/03 §3.3).

   A blank list is where a new family stops. So each shelf of the first session
   has a ready-made card — "SBI savings", "LIC policy", "PPF", "House papers",
   "Gold" — and one tap opens the ordinary form with the obvious parts filled
   in. Nothing is saved by the tap: every value lands in an editable field, and
   the person still presses Save.

   These are not the household's saved templates (the template module). Those
   are shapes a family made from its own records; these are the same few shapes
   every Indian family starts with, so they live with the client, in the
   reader's language, and need no round trip before the first record exists.

   A starter never fills in an identifier, an address or where something is
   kept. Those are the family's to type, and where it is kept is sealed on the
   saved record (docs/20).
   ============================================================================= */

import { api } from "./api.js";
import { state, typesFlat, update } from "./state.js";
import { t } from "./i18n.js";
import { toast } from "./ui.js";
import { captureForm } from "./screens/capture.js";
import { navigate } from "./app.js";

/**
 * kind: "investment" opens the capture form for `type`; "account", "liability"
 * and "estate" open the form on their own screen (see state.pendingForm).
 * `institution` is matched against the household's institutions by name.
 */
export const STARTERS = [
  { code: "sbi_savings", shelf: "savings_account", kind: "account", accountKind: "savings", institution: "State Bank of India" },
  { code: "sbi_fd", shelf: "fixed_deposit", kind: "investment", type: "fd", institution: "State Bank of India" },
  { code: "gold", shelf: "gold", kind: "investment", type: "gold_jewelry", attributes: { purity: "22k" } },
  { code: "health_cover", shelf: "health_insurance", kind: "investment", type: "insurance_health" },
  { code: "lic_policy", shelf: "life_insurance", kind: "investment", type: "insurance_endowment", institution: "LIC of India" },
  { code: "ppf", shelf: "ppf", kind: "investment", type: "ppf", institution: "India Post" },
  { code: "epf", shelf: "epf", kind: "investment", type: "epf" },
  { code: "sip", shelf: "mutual_funds", kind: "investment", type: "mf_sip" },
  { code: "shares", shelf: "shares", kind: "investment", type: "stock_listed" },
  { code: "nsc", shelf: "small_savings", kind: "investment", type: "nsc_kvp", institution: "India Post" },
  { code: "nps", shelf: "nps", kind: "investment", type: "nps" },
  { code: "home_loan", shelf: "loans", kind: "liability", liabilityKind: "home" },
  { code: "locker", shelf: "locker", kind: "account", accountKind: "locker" },
  { code: "house_papers", shelf: "property", kind: "investment", type: "property", attributes: { property_kind: "house" } },
  { code: "will", shelf: "will", kind: "estate", estateKind: "will" },
];

/** Goals a family names first. `{name}` is the child or person the goal is for. */
export const GOAL_STARTERS = [
  { code: "education", yearsAhead: 15 },
  { code: "wedding", yearsAhead: 20 },
  { code: "retirement", yearsAhead: 25 },
  { code: "home", yearsAhead: 7 },
];

export function starterFor(shelf) { return STARTERS.find((s) => s.shelf === shelf) || null; }

/** The card's own words: "SBI savings". */
export function starterTitle(starter) { return t(`starter.${starter.code}.title`); }

async function institutionId(name) {
  if (!name) return null;
  try {
    const found = await api.institutions(state.household.id, name);
    const match = found.find((i) => i.name.toLowerCase() === name.toLowerCase()) || found[0];
    return match?.id || null;
  } catch {
    return null;  // a starter is a convenience; the field simply stays empty
  }
}

/**
 * Opens the form a starter fills. `person` is the member the setup is for, when
 * it is for someone else (X-32): the form then says whose it is.
 */
export async function openStarter(starter, { onSaved, person } = {}) {
  const title = person
    ? t(`starter.${starter.code}.titleFor`, { name: person.displayName })
    : starterTitle(starter);

  if (starter.kind === "investment") {
    const type = typesFlat().find((x) => x.code === starter.type);
    if (!type) { toast(t("starter.unavailable")); return; }
    captureForm(type, onSaved, {
      title,
      institutionId: await institutionId(starter.institution),
      attributes: { ...(starter.attributes || {}) },
      ownerId: person?.id || null,
      fromStarter: starter.code,
    });
    return;
  }

  // The other forms belong to their own screens; the screen opens its form on
  // arrival, filled in the same way.
  const route = { account: "accounts", liability: "liabilities", estate: "continuity" }[starter.kind];
  update({
    pendingForm: {
      route,
      title,
      accountKind: starter.accountKind || null,
      liabilityKind: starter.liabilityKind || null,
      estateKind: starter.estateKind || null,
      institutionId: await institutionId(starter.institution),
      memberId: person?.id || null,
    },
  });
  navigate(route);
}
