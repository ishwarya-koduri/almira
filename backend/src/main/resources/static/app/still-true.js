/* "Still true?" — the records nobody has confirmed in a while (docs/21).

   A card on Home rather than a screen of its own: the question is small, and a
   yes should take one tap from the place people already look. It shows only
   what the server says this person is being asked about, which is only what
   they own or hold; nobody is asked to vouch for someone else's policy.

   The server answers an older client's missing endpoint with a 404, and Home
   must not break for that, so a failed load is simply no card. */

import { api } from "./api.js";
import { el, toast } from "./ui.js";
import { t, localDate } from "./i18n.js";

/** The list, or nothing. Never throws: this card is never why Home fails. */
export async function loadStillTrue(householdId) {
  try {
    const result = await api.stillTrue(householdId);
    return Array.isArray(result?.items) ? result.items : [];
  } catch {
    return [];
  }
}

/** One month on, as the household's calendar date the server validates. */
export function monthFromToday(now = new Date()) {
  const next = new Date(now.getFullYear(), now.getMonth() + 1, now.getDate());
  // 31 January plus a month is not 3 March: clamp to the month's last day.
  if (next.getDate() !== now.getDate()) next.setDate(0);
  const pad = (n) => String(n).padStart(2, "0");
  return `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`;
}

export function stillTrueCard(householdId, items) {
  if (!items.length) return null;

  const list = el("div.list", {});
  const count = el("span.caption.muted", {}, String(items.length));
  const card = el("div.card", {},
    el("div.section-title", {}, el("h4", {}, t("still.title")), count),
    el("p.caption.muted", { style: { marginTop: 0 } }, t("still.intro")),
    list,
  );

  const remove = (row) => {
    row.remove();
    count.textContent = String(list.children.length);
    if (!list.children.length) card.remove();
  };

  items.forEach((item) => {
    const buttons = [];
    const busy = (on) => buttons.forEach((b) => { b.disabled = on; });

    const confirm = el("button.btn.btn-sm.btn-primary", {
      type: "button",
      onclick: async () => {
        busy(true);
        try {
          const answered = await api.confirmStillTrue(householdId, item.recordType, item.recordId);
          toast(t("still.confirmed", { date: localDate(answered.dueOn) }));
          remove(row);
        } catch (error) {
          busy(false);
          toast(error.message, { tone: "error" });
        }
      },
    }, t("still.confirm"));

    const later = el("button.btn.btn-sm", {
      type: "button",
      onclick: async () => {
        busy(true);
        try {
          const snoozed = await api.snoozeStillTrue(
            householdId, item.recordType, item.recordId, monthFromToday());
          toast(t("still.snoozed", { date: localDate(snoozed.dueOn) }));
          remove(row);
        } catch (error) {
          busy(false);
          toast(error.message, { tone: "error" });
        }
      },
    }, t("still.snooze"));
    buttons.push(confirm, later);

    const why = item.reason === "key_date" && item.keyDate
      ? t("still.keyDate", { date: localDate(item.keyDate) })
      : t("still.lastConfirmed", { date: localDate(item.lastConfirmedAt) });

    // The answers sit under the title rather than beside it: beside it, on a
    // phone, two buttons squeezed the title to one word a line.
    const row = el("div.list-row", { style: { alignItems: "flex-start" } },
      el("span.pill", {}, t(`where.type.${item.recordType}`)),
      el("div.grow", {},
        el("div.title", {}, item.title),
        el("div.meta", {}, why),
        el("div.row.wrap", { style: { gap: "8px", marginTop: "10px" } }, confirm, later),
      ),
    );
    list.append(row);
  });

  return card;
}
