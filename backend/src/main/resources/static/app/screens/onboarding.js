/* =============================================================================
   The first session, before there is a household (docs/03 §1).

   Three short steps, each one skippable:

     1. "How ready is your family?" — eight questions about paperwork, never
        about money, answered yes / partly / no / not sure. It gives something
        back before a single record exists: the three gaps that matter most
        (X-30). Stored per person, on the server, so it is still there on the
        next phone.
     2. Who this is for — "For me", or for a parent or someone else (X-32). An
        adult child typing for Amma is how this usually happens in an Indian
        family; from here on the screens speak about her, and her records are
        hers.
     3. The household itself, then straight to the almirah's shelves (P-10).

   docs/03 §1 still holds: a first record within two minutes. Skipping the check
   is one tap, and nothing here asks for anything the next screen needs.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, field, textInput, select, withBusy, notice } from "../ui.js";
import { privacyLink } from "../privacy.js";
import { t } from "../i18n.js";

/** The server's order; kept here too so the questions draw before it answers. */
const QUESTIONS = ["will", "nominees", "papers", "second_person", "one_list", "insurance", "loans", "locker"];
const ANSWERS = ["yes", "partly", "no", "not_sure"];

export function onboardingScreen(onDone) {
  const host = el("main.narrow", {}, el("div.skeleton", { style: { height: "240px" } }));
  const answers = {};
  let result = null;

  // Setup choices survive stepping back and forth.
  let forWhom = "me";
  let mode = "just_me";
  let visibility = "private";
  const nameInput = textInput({ placeholder: t("onboard.householdPlaceholder"), "aria-label": t("onboard.householdName") });
  const displayInput = textInput({ placeholder: t("onboard.yourNamePlaceholder"), "aria-label": t("onboard.yourName"), autocomplete: "name" });
  const personInput = textInput({ placeholder: t("onboard.personPlaceholder"), "aria-label": t("onboard.personName") });
  const relationship = select({
    options: [
      { value: "parent", label: t("onboard.rel.parent") },
      { value: "spouse", label: t("onboard.rel.spouse") },
      { value: "other", label: t("onboard.rel.other") },
    ],
    value: "parent",
    "aria-label": t("onboard.personRelationship"),
  });

  const choice = (value, current, title, description, onPick) => el("button.choice", {
    type: "button", "aria-pressed": value === current, onclick: onPick,
  },
    el("div", {}, el("div.ct", {}, title), description && el("div.cd", {}, description)),
    el("span.radio", { "aria-hidden": "true" }),
  );

  // Each step moves focus to its heading, so a screen reader hears the new
  // question rather than silence.
  const draw = (...children) => {
    mount(host, el("div.stack", {}, ...children));
    host.querySelector("h1")?.focus();
  };
  const heading = (text) => el("h1", { tabIndex: -1 }, text);

  /* --- 1. the readiness check --------------------------------------------- */

  function intro() {
    draw(
      el("div.stack-2", {},
        el("span.overline", {}, t("check.overline")),
        heading(t("check.title")),
        el("p.muted", {}, t("check.intro")),
      ),
      el("div.row.wrap", {},
        el("button.btn.btn-primary", { type: "button", onclick: () => question(0) }, t("check.start")),
        el("button.btn.btn-ghost", { type: "button", onclick: () => setup() }, t("check.skip")),
      ),
      el("div.row", {}, privacyLink()),
    );
  }

  function question(index) {
    const code = QUESTIONS[index];
    const progress = t("check.progress", { n: index + 1, total: QUESTIONS.length });
    const next = () => (index + 1 < QUESTIONS.length ? question(index + 1) : finish());
    draw(
      el("div.stack-2", {},
        el("span.overline", {}, progress),
        el("div.meter", { role: "img", "aria-label": progress },
          el("div.meter-fill", { style: { width: `${((index + 1) / QUESTIONS.length) * 100}%` } })),
      ),
      el("div.stack-2", {},
        heading(t(`check.q.${code}`)),
        el("p.muted", { style: { margin: 0 } }, t(`check.q.${code}.help`)),
      ),
      el("div.choices", { role: "group", "aria-label": t(`check.q.${code}`) },
        ...ANSWERS.map((answer) => choice(answer, answers[code], t(`check.a.${answer}`), null, () => {
          answers[code] = answer;
          next();
        })),
      ),
      el("div.row-between", {},
        index > 0
          ? el("button.btn.btn-ghost", { type: "button", onclick: () => question(index - 1) }, t("check.back"))
          : el("span"),
        el("button.btn.btn-ghost", { type: "button", onclick: () => { delete answers[code]; next(); } }, t("check.pass")),
      ),
    );
  }

  async function finish() {
    if (Object.keys(answers).length === 0) { setup(); return; }
    try {
      result = await api.answerReadinessCheck(answers);
    } catch {
      result = null;
    }
    showResult();
  }

  function showResult() {
    const gaps = result?.gaps || [];
    const answered = Object.keys(answers).length;
    draw(
      el("div.stack-2", {},
        el("span.overline", {}, t("check.overline")),
        heading(result && gaps.length === 0 ? t("check.result.none") : t("check.result.title")),
        result && el("p.muted", { style: { margin: 0 } },
          t("check.result.ready", { ready: result.readyCount, total: answered })),
      ),
      gaps.length > 0 && el("ol.gap-list", {},
        ...gaps.map((gap, index) => el("li.card.stack-2", {},
          el("span.overline", {}, t("check.result.gapNumber", { n: index + 1 })),
          el("h3", {}, t(`check.gap.${gap.question}`)),
          el("p", { style: { margin: 0 } }, t(`check.gap.${gap.question}.do`)),
        ))),
      // The three gaps are worked out on the server; with no answer from it
      // there is no ranking to show, and the page says so rather than guessing.
      !result && notice(t("check.notSaved")),
      notice(t("check.notAdvice")),
      el("div.row", {},
        el("button.btn.btn-primary", { type: "button", onclick: () => setup() }, t("check.continue"))),
    );
  }

  /* --- 2 and 3. who it is for, and the household ---------------------------- */

  function setup(moveFocus = true) {
    const submit = el("button.btn.btn-primary", { type: "submit" }, t("onboard.create"));
    const error = el("div.help.error", { role: "alert" });

    const form = el("form.stack", {
      onsubmit: async (event) => {
        event.preventDefault();
        error.textContent = "";
        const personName = personInput.value.trim();
        if (forWhom === "someone" && !personName) {
          error.textContent = t("onboard.personNeeded");
          personInput.focus();
          return;
        }
        await withBusy(submit, async () => {
          try {
            const household = await api.createHousehold({
              name: nameInput.value.trim() || null,
              mode: forWhom === "someone" ? "family" : mode,
              defaultVisibility: visibility,
              displayName: displayInput.value.trim() || null,
            });
            if (forWhom === "someone") {
              // The household exists now, so a failure here must not offer to
              // create a second one: carry on, and the person can be added from
              // Household instead.
              try {
                const person = await api.addMember(household.id, { displayName: personName, relationship: relationship.value });
                await api.updateFirstSession(household.id, { settingUpFor: "someone", someoneMemberId: person.id });
              } catch { /* see above */ }
            }
            await onDone({ openShelves: true });
          } catch (apiError) {
            error.textContent = apiError.message;
          }
        });
      },
    },
      el("div.stack-2", {},
        heading(t("onboard.title")),
        el("p.muted", { style: { margin: 0 } }, t("onboard.intro")),
      ),

      el("div.card.stack-3", {},
        el("h4", {}, t("onboard.forWhom")),
        el("div.choices", {},
          choice("me", forWhom, t("onboard.forMe"), t("onboard.forMeHelp"), () => { forWhom = "me"; setup(false); }),
          choice("someone", forWhom, t("onboard.forSomeone"), t("onboard.forSomeoneHelp"), () => { forWhom = "someone"; setup(false); }),
        ),
        forWhom === "someone" && el("div.stack-3", {},
          field({ label: t("onboard.personName"), control: personInput, required: true, help: t("onboard.personHelp") }),
          field({ label: t("onboard.personRelationship"), control: relationship }),
          el("p.caption", { style: { margin: 0 } }, t("onboard.someoneVisibility")),
        ),
      ),

      forWhom === "me" && el("div.card.stack-3", {},
        el("h4", {}, t("onboard.whoTracking")),
        el("div.choices", {},
          choice("just_me", mode, t("onboard.justMe"), t("onboard.justMeHelp"), () => { mode = "just_me"; setup(false); }),
          choice("family", mode, t("onboard.family"), t("onboard.familyHelp"), () => { mode = "family"; setup(false); }),
        ),
      ),

      el("div.card.stack-3", {},
        el("h4", {}, t("onboard.defaultTitle")),
        el("p.caption", { style: { margin: 0 } }, t("onboard.defaultHelp")),
        el("div.choices", {},
          choice("private", visibility, t("onboard.private"), t("onboard.privateHelp"), () => { visibility = "private"; setup(false); }),
          choice("household", visibility, t("onboard.shared"), t("onboard.sharedHelp"), () => { visibility = "household"; setup(false); }),
        ),
      ),

      el("div.card.stack-3", {},
        field({ label: t("onboard.householdName"), control: nameInput, help: t("onboard.householdHelp") }),
        field({ label: t("onboard.yourName"), control: displayInput, help: t("onboard.yourNameHelp") }),
        error,
        el("div.row", {}, submit),
      ),
      el("div.row", {}, privacyLink()),
    );
    mount(host, form);
    // A choice redraws the form; only arriving at it moves focus.
    if (moveFocus) host.querySelector("h1")?.focus();
  }

  // Someone who has taken the check before (another phone, an earlier visit)
  // goes straight to setting up; the check is still in the guide.
  api.readinessCheck()
    .then((existing) => { if (existing?.answered) setup(); else intro(); })
    .catch(() => intro());
  return host;
}
