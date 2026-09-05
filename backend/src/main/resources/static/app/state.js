/* Shared application state. Small enough to be a plain object with subscribers;
   a store library would be more machinery than this client earns. */

export const state = {
  user: null,
  households: [],
  household: null,
  members: [],
  taxonomy: [],
  scope: "household",
  scopeMember: null,
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
