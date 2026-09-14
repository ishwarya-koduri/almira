/* =============================================================================
   What we measure (docs/what-we-measure.md).

   Almira counts twelve things per day — how many people signed in, added a
   first holding, gave up on the add form at which step — and never who, how
   much, or what. The counting happens on our own server; there is no script,
   cookie or third party on this page for it. This file is the two things a
   person can see of it: the opt-out in Settings, and the list, in plain words.

   It also reports the one event only the client can see: an add form closed
   without saving. The request carries the step and nothing else, and failing
   to send it is silent — it must never get in the way of the person closing it.
   ============================================================================= */

import { api } from "./api.js";
import { el, sheet, toast } from "./ui.js";
import { t } from "./i18n.js";

/** In the order docs/what-we-measure.md lists them. */
const EVENTS = [
  "sign_in_completed", "household_created", "invite_sent", "invite_accepted",
  "first_holding_added", "holding_added", "liability_added", "document_uploaded",
  "estate_contact_added", "still_true_confirmed", "handbook_printed", "capture_abandoned",
];

export function reportCaptureAbandoned(step) {
  api.post("/api/v1/measurement/abandoned", { form: "capture", step }).catch(() => {});
}

export function openWhatWeMeasure() {
  return sheet({
    title: t("measure.title"),
    body: el("div.stack-3", {},
      el("p", { style: { margin: 0 } }, t("measure.intro")),
      el("ul.stack-2", { style: { margin: 0, paddingLeft: "1.2em" } },
        ...EVENTS.map((code) => el("li", {}, t(`measure.event.${code}`)))),
      el("h4", {}, t("measure.never.title")),
      el("p", { style: { margin: 0 } }, t("measure.never.body")),
    ),
  });
}

export async function measurementCard() {
  const preference = await api.get("/api/v1/me/measurement");
  const box = el("input", {
    type: "checkbox",
    checked: !preference.optedOut,
    style: { width: "22px", height: "22px", margin: "11px" },
  });
  box.addEventListener("change", async () => {
    box.disabled = true;
    try {
      const updated = await api.put("/api/v1/me/measurement", { optedOut: !box.checked });
      box.checked = !updated.optedOut;
      toast(updated.optedOut ? t("measure.off") : t("measure.on"));
    } catch (error) {
      box.checked = !box.checked;
      toast(error.message);
    } finally {
      box.disabled = false;
    }
  });

  return el("div.card.stack-3", {},
    el("h4", {}, t("measure.title")),
    el("label.row", { style: { alignItems: "flex-start", gap: "4px", minHeight: "44px" } },
      box,
      el("div", { style: { paddingTop: "10px" } },
        el("div", {}, t("measure.toggle")),
        el("div.caption.muted", {}, t("measure.summary")),
      ),
    ),
    el("div.row", {},
      el("button.btn.btn-ghost.btn-sm", { type: "button", onclick: () => openWhatWeMeasure() },
        t("measure.open"))),
  );
}
