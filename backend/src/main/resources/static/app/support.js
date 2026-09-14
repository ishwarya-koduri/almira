/* =============================================================================
   Get help: share a support code (docs/27 §4).

   Support can't see a family's records and doesn't need to. The person sees
   exactly what a code would share — asked of the server, not guessed here — and
   what it never shares, before it exists. The code is shown once, large enough
   to read aloud, and the card lists every code still working with how many
   times support opened it and a way to take it back.

   What this client offers is deliberately bare: the version of the cached app,
   the last screen that was not Settings, the language, the last error codes the
   server answered with (codes only, kept in memory by api.js), and three
   switches. Never a hash with an id in it, never a message, never an amount.
   ============================================================================= */

import { api, recentErrorCodes } from "./api.js";
import { el, mount, sheet, toast, withBusy, formatDate, notice } from "./ui.js";
import { t, language } from "./i18n.js";
import { prefs, savingData } from "./prefs.js";

let lastScreen = null;

/** The shell calls this on every render. Settings itself is where help is asked for, so it is not a clue. */
export function noteScreen(route) {
  if (route && route !== "settings" && /^[a-z][a-z0-9-]{0,39}$/.test(route)) lastScreen = route;
}

/** The service worker's shell cache is named after the build, which is the one version this page knows. */
async function appVersion() {
  try {
    const shell = (await caches.keys()).find((name) => name.endsWith("-shell"));
    const version = shell?.slice(0, -"-shell".length);
    return version && /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$/.test(version) ? version : null;
  } catch {
    return null;
  }
}

async function diagnosticsBody() {
  return {
    appVersion: await appVersion(),
    platform: "web",
    screen: lastScreen,
    language: language.code,
    errorCodes: recentErrorCodes(),
    flags: {
      dataSaver: savingData(),
      largerText: prefs.text !== "standard",
      clearTheme: prefs.theme === "clear",
    },
  };
}

const LABELS = {
  appVersion: "support.key.appVersion",
  platform: "support.key.platform",
  screen: "support.key.screen",
  language: "support.key.language",
  errorCodes: "support.key.errorCodes",
  flags: "support.key.flags",
  apiVersion: "support.key.apiVersion",
  households: "support.key.households",
  anyHouseholdReadOnly: "support.key.readOnly",
};

function valueText(key, value) {
  if (value === null || value === undefined || value === "") return t("support.none");
  if (key === "flags") {
    const on = Object.entries(value).filter(([, v]) => v).map(([name]) => name);
    return on.length ? on.join(", ") : t("support.none");
  }
  if (Array.isArray(value)) return value.length ? value.join(", ") : t("support.none");
  if (typeof value === "boolean") return value ? t("support.yes") : t("support.no");
  return String(value);
}

/** Every key the server says it will share, in its order, with nothing left out. */
function sharesList(diagnostics) {
  return el("dl.shares", {},
    ...Object.entries(diagnostics).flatMap(([key, value]) => [
      el("dt", {}, LABELS[key] ? t(LABELS[key]) : key),
      el("dd", {}, valueText(key, value)),
    ]));
}

function openShare(onChanged) {
  const body = el("div.stack-3", {}, el("p.caption", {}, t("support.loading")));
  const make = el("button.btn.btn-primary", { type: "button", disabled: true }, t("support.make"));
  const modal = sheet({ title: t("support.shareTitle"), body, footer: [make] });

  let request;
  diagnosticsBody()
    .then((prepared) => { request = prepared; return api.post("/api/v1/me/support-codes/preview", prepared); })
    .then((preview) => {
      mount(body,
        el("p", { style: { margin: 0 } }, t("support.intro", { hours: preview.validForHours })),
        el("span.overline", {}, t("support.sharesTitle")),
        sharesList(preview.diagnostics),
        el("span.overline", {}, t("support.neverTitle")),
        el("ul.promise", {}, ...NEVER.map((key) => el("li", {}, t(key)))),
      );
      make.disabled = false;
    })
    .catch((error) => mount(body, notice(error.message, { tone: "alert", role: "alert" })));

  make.onclick = () => withBusy(make, async () => {
    const created = await api.post("/api/v1/me/support-codes", request);
    mount(body,
      el("p", { style: { margin: 0 } }, t("support.readThis")),
      el("p.support-code", { "aria-label": created.code.split("").join(" ") }, created.code),
      el("p.caption", { style: { margin: 0, textAlign: "center" } },
        t("support.until", { date: formatDate(created.supportCode.expiresAt), time: timeOf(created.supportCode.expiresAt) })),
      notice(t("support.shownOnce")),
    );
    // The footer held only this button; an empty bar under the code reads as unfinished.
    (make.closest(".sheet-foot") || make).remove();
    onChanged();
  }).catch((error) => toast(error.message, { tone: "error" }));

  return modal;
}

function timeOf(iso) {
  return new Date(iso).toLocaleTimeString({ en: "en-IN", te: "te-IN", hi: "hi-IN" }[language.code] || "en-IN",
    { hour: "numeric", minute: "2-digit" });
}

const NEVER = ["support.never.amounts", "support.never.names", "support.never.titles", "support.never.documents", "support.never.contact"];

function codeRow(code, onChanged) {
  const takeBack = el("button.btn.btn-sm", { type: "button" }, t("support.takeBack"));
  takeBack.onclick = () => withBusy(takeBack, async () => {
    await api.post(`/api/v1/me/support-codes/${code.id}/revoke`);
    toast(t("support.takenBack"));
    onChanged();
  }).catch((error) => toast(error.message, { tone: "error" }));

  return el("li.support-row", {},
    el("div", {},
      el("div", {}, t("support.madeOn", { date: formatDate(code.createdAt), time: timeOf(code.createdAt) })),
      el("div.caption", {},
        t("support.worksUntil", { time: timeOf(code.expiresAt) }), " · ",
        code.lookups === 0 ? t("support.notOpened")
          : code.lookups === 1 ? t("support.openedOnce")
            : t("support.opened", { count: code.lookups })),
    ),
    takeBack,
  );
}

export async function supportCard() {
  const host = el("div.card.stack-3", {});
  const draw = async () => {
    const codes = await api.get("/api/v1/me/support-codes").catch(() => []);
    const working = codes.filter((code) => code.active);
    mount(host,
      el("h4", {}, t("support.title")),
      el("p.caption", { style: { margin: 0 } }, t("support.summary")),
      working.length > 0 && el("ul.plain-list", {}, ...working.map((code) => codeRow(code, draw))),
      el("div.row", {},
        el("button.btn", { type: "button", onclick: () => openShare(draw) }, t("support.share"))),
    );
  };
  await draw();
  return host;
}
