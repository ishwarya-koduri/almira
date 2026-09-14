/* Notifications in Settings — what reaches you, when, and the promise behind it
   (docs/13 "Pacing").

   Two cards. "Our quiet promise" says, in three plain lines, what Almira will and
   will not do with a person's attention; it is shown to everyone, whatever the
   server has configured, because it is a promise about the product and not
   about a channel. "Notifications" holds the choices that keep it: which
   channels, and the quiet hours.

   The choices save as they are made — there is no Save button to forget — and
   a failed save puts the control back and says so. A server from before these
   preferences existed answers 404, and then there is simply no second card. */

import { api } from "./api.js";
import { el, toast, field, textInput } from "./ui.js";
import { t, localDate } from "./i18n.js";

/** The promise card. Static on purpose: nothing here depends on the server. */
export function quietPromiseCard() {
  return el("div.card.stack-3", { "data-quiet-promise": "" },
    el("h4", {}, t("notify.promise.title")),
    el("ul.promise", {},
      el("li", {}, t("notify.promise.oneADay")),
      el("li", {}, t("notify.promise.noSales")),
      el("li", {}, t("notify.promise.why")),
    ),
    el("p.caption.muted.note", { style: { margin: 0 } },
      el("span.info-mark", { "aria-hidden": "true" }, "i"),
      t("notify.promise.essential")),
  );
}

/** The preferences card, or null when this server has none. */
export async function notificationsCard() {
  let prefs;
  try {
    prefs = await api.notificationPreferences();
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }

  const save = async (change, undo) => {
    try {
      prefs = await api.updateNotificationPreferences(change);
      toast(t("notify.saved"));
    } catch (error) {
      undo();
      toast(error.message, { tone: "error" });
    }
  };

  const channelRow = (status) => {
    const key = `${status.channel}Enabled`;
    const box = el("input", {
      type: "checkbox",
      checked: Boolean(prefs[key]),
      disabled: !status.offered,
    });
    box.addEventListener("change", () => {
      const now = box.checked;
      save({ [key]: now }, () => { box.checked = !now; });
    });
    const help = !status.offered
      ? t("notify.channel.notOffered")
      : !status.reachable ? t(`notify.channel.unreachable.${status.channel}`) : "";
    if (help) box.setAttribute("aria-describedby", `notify-${status.channel}-help`);
    return el("label.check-row", {},
      box,
      el("span.grow", {},
        el("span.check-label", {}, t(`notify.channel.${status.channel}`)),
        help && el("span.caption.muted", { id: `notify-${status.channel}-help` }, help),
      ),
    );
  };

  const from = textInput({ type: "time", value: prefs.quietFrom, "aria-label": t("notify.quiet.from") });
  const until = textInput({ type: "time", value: prefs.quietUntil, "aria-label": t("notify.quiet.until") });
  const quietField = field({
    label: t("notify.quiet.title"),
    control: el("div.quiet-hours", {},
      el("span.caption.muted", {}, t("notify.quiet.from")), from,
      el("span.caption.muted", {}, t("notify.quiet.until")), until),
    help: t("notify.quiet.help"),
  });
  const saveQuiet = () => {
    const before = { quietFrom: prefs.quietFrom, quietUntil: prefs.quietUntil };
    if (!from.value || !until.value) return;
    if (from.value === before.quietFrom && until.value === before.quietUntil) return;
    save({ quietFrom: from.value, quietUntil: until.value }, () => {
      from.value = before.quietFrom;
      until.value = before.quietUntil;
    });
  };
  from.addEventListener("change", saveQuiet);
  until.addEventListener("change", saveQuiet);

  return el("div.card.stack-3", { "data-notification-preferences": "" },
    el("h4", {}, t("notify.title")),
    el("p.caption.muted", { style: { margin: 0 } }, t("notify.intro")),
    el("div.stack-2", {}, ...prefs.channels.map(channelRow)),
    quietField,
    prefs.stillTruePausedUntil && el("p.caption.muted.note", { style: { margin: 0 } },
      el("span.info-mark", { "aria-hidden": "true" }, "i"),
      t("notify.stillTruePaused", { date: localDate(prefs.stillTruePausedUntil) })),
  );
}
