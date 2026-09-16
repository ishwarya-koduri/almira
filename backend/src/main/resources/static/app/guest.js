/* =============================================================================
   The page a guest link opens (known issue 43), and a helper's link from heir
   mode (X-40).

   Nobody is signed in here. The token is read from this page's own address and
   sent only to `/api/v1/share/<token>` (or `…/tasks`), where it is checked and
   where the database narrows every read to what the link names. Nothing is
   stored: not the token, not the payload — the service worker never caches an
   API response, and this page writes nothing to storage.

   Read-only by construction: there is not one control here that changes
   anything.
   ============================================================================= */

import { el, mount, notice, formatDate } from "./ui.js";
import { apply as applyPrefs } from "./prefs.js";
import { t, language } from "./i18n.js";

applyPrefs();
document.documentElement.lang = language.code;

const root = document.getElementById("root");
const match = location.pathname.match(/^\/(share|help)\/([A-Za-z0-9_-]{20,100})$/);

function page(...children) {
  return el("main.guest", {}, el("div.stack", {}, ...children));
}

function failed() {
  mount(root, page(
    el("h1", {}, t("guest.gone.title")),
    el("p.muted", {}, t("guest.gone.body")),
  ));
}

async function load(path) {
  const response = await fetch(path, { headers: { Accept: "application/json" }, credentials: "omit", cache: "no-store" });
  if (!response.ok) return null;
  return response.json();
}

function helperPage(view) {
  document.title = t("guest.help.title");
  return page(
    el("div.stack-2", {},
      el("span.overline", {}, t("guest.help.overline")),
      el("h1", {}, t("guest.help.hello", { name: view.helperName })),
      el("p", {}, t("guest.help.intro", { who: view.sharedBy, name: view.subjectName || "" })),
    ),
    view.tasks.length === 0
      ? el("p.muted", {}, t("guest.help.none"))
      : el("div.stack-3", {}, ...view.tasks.map((task) => el("section.card.stack-2", {},
          el("h2.heir-title", {}, task.title),
          el("p", {}, task.why),
          task.whereToStart && el("p.caption", {}, t("heir.startWith", { where: task.whereToStart })),
          task.steps.length > 0 && el("ol.heir-steps", {}, ...task.steps.map((step) => el("li", {},
            el("b", {}, step.step), step.detail && el("span.muted", {}, step.detail)))),
          task.status === "done" && notice(t("heir.doneOn", { date: formatDate(task.doneAt) })),
        ))),
    notice(view.note),
    el("p.caption", {}, t("guest.until", { date: formatDate(view.expiresAt) })),
  );
}

function handbookBlock(book) {
  return el("div.stack-3", {},
    el("section.card.stack-3", {},
      el("h2", {}, t("guest.handbook.what")),
      book.entries.length === 0
        ? el("p.muted", {}, t("guest.handbook.empty"))
        : el("div.list", {}, ...book.entries.map((entry) => el("div.list-row", {},
            el("div", {},
              el("div.title", {}, entry.title),
              el("div.meta", {}, [entry.typeLabel, entry.institutionName, entry.reference].filter(Boolean).join(" · ")),
              entry.nominees.length > 0 && el("div.meta", {}, `${t("continuity.nominee")}: ${entry.nominees.join(", ")}`),
              el("div.caption", {}, entry.howToClaim),
            ),
            entry.valueFormatted && el("div.amount", {}, entry.valueFormatted),
          ))),
      book.excludedCount > 0 && el("p.caption", {}, book.note),
    ),
    book.debts.length > 0 && el("section.card.stack-2", {},
      el("h2", {}, t("guest.handbook.owed")),
      ...book.debts.map((debt) => el("div.row-between.wrap", {},
        el("span", {}, `${debt.title}${debt.lender ? ` · ${debt.lender}` : ""}`),
        debt.outstandingFormatted && el("span.owed", {}, debt.outstandingFormatted),
      )),
    ),
    book.instruments.length > 0 && el("section.card.stack-2", {},
      el("h2", {}, t("estate.title")),
      ...book.instruments.map((item) => el("div", {},
        el("b", {}, item.title),
        item.executors.length > 0 && el("div.caption", {}, `${t("estate.executor")}: ${item.executors.join(", ")}`),
      )),
    ),
    book.contacts.length > 0 && el("section.card.stack-2", {},
      el("h2", {}, t("guest.handbook.call")),
      ...book.contacts.map((contact) => el("div.row-between.wrap", {},
        el("span", {}, contact.name),
        contact.phone && el("a", { href: `tel:${contact.phone.replace(/[^\d+]/g, "")}` }, contact.phone),
      )),
    ),
    el("p.caption", {}, book.disclaimer),
  );
}

function sharePage(payload, token) {
  document.title = payload.label;
  return page(
    el("div.stack-2", {},
      el("span.overline", {}, payload.householdName),
      el("h1", {}, payload.label),
      el("p.muted", {}, t("guest.sharedBy", { who: payload.sharedBy, date: formatDate(payload.expiresAt) })),
      payload.note && el("p", {}, payload.note),
    ),
    payload.handbook && handbookBlock(payload.handbook),
    payload.taxPack && el("section.card.stack-2", {},
      el("h2", {}, t("guest.taxPack")),
      el("a.btn.btn-primary", { href: `/api/v1/share/${token}/tax-pack.pdf`, rel: "noreferrer" }, t("guest.taxPack.pdf")),
      el("a.btn", { href: `/api/v1/share/${token}/schedule-112a.csv`, rel: "noreferrer" }, t("guest.taxPack.csv")),
    ),
    payload.records.length > 0 && el("section.card", {},
      el("div.list", {}, ...payload.records.map((record) => el("div.list-row", {},
        el("div", {},
          el("div.title", {}, record.title),
          el("div.meta", {}, [record.typeLabel, record.institutionName].filter(Boolean).join(" · ")),
        ),
        record.value && el("div.amount", {}, record.value),
      )))),
    notice(payload.notice),
  );
}

(async function start() {
  if (!match) { failed(); return; }
  const [, kind, token] = match;
  try {
    if (kind === "help") {
      const view = await load(`/api/v1/share/${token}/tasks`);
      if (view) mount(root, helperPage(view)); else failed();
    } else {
      const payload = await load(`/api/v1/share/${token}`);
      if (payload) mount(root, sharePage(payload, token)); else failed();
    }
  } catch {
    failed();
  }
})();
