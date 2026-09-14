/* Shared application state. Small enough to be a plain object with subscribers;
   a store library would be more machinery than this client earns. */

export const state = {
  user: null,
  households: [],
  household: null,
  members: [],
  taxonomy: [],
  /** The household's plan (docs/28), or null when it could not be read. */
  plan: null,
  scope: "household",
  scopeMember: null,
  /** The first session (shelves, who it is for), once loaded. */
  firstSession: null,
  /** See takePendingForm. */
  pendingForm: null,
};

const listeners = new Set();

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function update(patch) {
  Object.assign(state, patch);
  listeners.forEach((listener) => listener(state));
}

/** The current user's own member row — the "Me" scope, and the capture default. */
export function myMember() {
  return state.members.find((m) => m.isMe) || null;
}

export function typesFlat() {
  return state.taxonomy.flatMap((category) =>
    category.types.map((type) => ({ ...type, categoryColor: category.color })));
}

export function findType(typeId) {
  return typesFlat().find((type) => type.id === typeId) || null;
}

/**
 * A form another screen asked this one to open on arrival (X-31): a starter
 * for an account, a loan or a will opens that screen's own form, filled in.
 * Taken once, so going back to the screen later does not open it again.
 */
export function takePendingForm(route) {
  const pending = state.pendingForm;
  if (!pending || pending.route !== route) return null;
  state.pendingForm = null;
  return pending;
}

/**
 * Who the first session is about (X-32): the member row when the setup is for
 * someone else, or null when it is for the person signed in.
 */
export function helpingWhom() {
  const session = state.firstSession;
  if (!session || session.settingUpFor !== "someone" || !session.someoneMemberId) return null;
  return state.members.find((m) => m.id === session.someoneMemberId) || null;
}

/**
 * What a new record's "Who can see this?" starts at. Normally the person's own
 * default. When they are setting things up for someone else and the record is
 * that person's, it starts at Shared with the household: only a holder may
 * share a record with named people (V8), so a Private record in Amma's name is
 * one her helper could never read back. The choice stays editable, and Amma can
 * make it private when she joins.
 */
export function startingVisibility(ownerMemberId) {
  const helping = helpingWhom();
  if (helping && ownerMemberId === helping.id) return "household";
  return state.user?.defaultVisibility || state.household?.defaultVisibility || "private";
}
