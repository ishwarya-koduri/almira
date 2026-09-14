/* Institutions and accounts — "which bank funds which SIP" (docs/01 §4).

   Note what this screen does NOT show by default: a full account number. Only
   the last four digits are kept unless someone explicitly asks otherwise, and
   even then seeing the rest takes a fresh confirmation. */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, select, skeletonRows, empty,
  withBusy, toast,
} from "../ui.js";
import { state, myMember, takePendingForm, startingVisibility } from "../state.js";
import { whereWhoCard } from "../where.js";
import { sealedNoteCard } from "../sealed-notes.js";
import { reload } from "../app.js";
import { helpMark } from "../glossary.js";
import { t } from "../i18n.js";

// Labels are looked up when drawn, so a change of language reaches them.
const KIND_CODES = ["savings", "current", "demat", "folio", "wallet", "locker", "other"];
const kinds = () => KIND_CODES.map((value) => ({ value, label: t(`accounts.kind.${value}`) }));
const kindLabel = (code) => (KIND_CODES.includes(code) ? t(`accounts.kind.${code}`) : code);

export async function accountsScreen(host) {
  mount(host, el("div.stack", {}, skeletonRows(3)));
  const rows = await api.accounts(state.household.id);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, t("nav.accounts")),
      el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, t("accounts.add")),
    ),
    el("p.muted", { style: { marginTop: "-12px" } }, t("accounts.lead")),

    rows.length === 0
      ? el("div.card", {}, empty({
          title: t("accounts.empty.title"),
          body: t("accounts.empty.body"),
          action: el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, t("accounts.add")),
        }))
      : el("div.card.card-tight", {},
          el("div.list", {}, ...rows.map((row) => el("button.list-row", {
            type: "button", onclick: () => openDetail(row.id),
          },
            el("div.grow", { style: { minWidth: 0 } },
              el("div.title", {}, row.label),
              el("div.meta", {}, [
                kindLabel(row.accountKind),
                row.institutionName,
                row.numberMasked,
                row.holders.map((h) => h.name).filter(Boolean).join(" & "),
              ].filter(Boolean).join(" · ")),
            ),
            el("div.amount", {},
              el("div.meta", {},
                row.linkedInvestmentCount === 0
                  ? t("accounts.nothingLinked")
                  : t("accounts.linked", { count: row.linkedInvestmentCount })),
            ),
            row.hasFullNumber && el("span.pill.pill-accent", { title: t("accounts.fullStored") },
              el("span", { "aria-hidden": "true" }, t("accounts.full")), el("span.sr-only", {}, t("accounts.fullStored"))),
          ))),
        ),
  ));

  // A starter on the first session's shelves asked for this form (X-31).
  const pending = takePendingForm("accounts");
  if (pending) openForm(pending);

  // ---------------------------------------------------------------- form ---

  function openForm(prefill = null) {
    const holderId = prefill?.memberId || myMember()?.id;
    const label = textInput({ placeholder: t("accounts.nameExample"), value: prefill?.title || "" });
    const kind = select({ options: kinds(), value: prefill?.accountKind || "savings" });
    const number = textInput({ placeholder: t("accounts.number") });
    const ifsc = textInput({ placeholder: "SBIN0001234" });

    const storeFull = el("input", { type: "checkbox" });
    const storeFullRow = el("label.row", { style: { alignItems: "flex-start", gap: "8px" } },
      storeFull,
      el("div", {},
        el("div", { style: { fontSize: "var(--text-sm)" } }, t("accounts.keepWhole")),
        el("div.caption.muted", {}, t("accounts.keepWholeHelp")),
      ),
    );

    const visibility = select({
      options: [
        { value: "private", label: t("accounts.visibility.private") },
        { value: "household", label: t("common.sharedWith", { name: state.household.name }) },
      ],
      value: startingVisibility(holderId),
    });

    const institution = select({
      options: [{ value: "", label: t("common.noInstitution") }],
    });
    api.institutions(state.household.id).then((list) => {
      list.forEach((i) => institution.append(el("option", { value: i.id }, i.name)));
      if (prefill?.institutionId) institution.value = prefill.institutionId;
    }).catch(() => { /* optional */ });

    const labelField = field({ label: t("common.whatToCallIt"), control: label, required: true });
    const save = el("button.btn.btn-primary.grow", { type: "button" }, t("app.save"));

    const modal = sheet({
      title: t("accounts.addTitle"),
      body: el("div.stack-3", {},
        labelField,
        field({ label: [t("common.whatKind"), helpMark("folio")], control: kind }),
        field({ label: t("accounts.whichBank"), control: institution }),
        field({ label: t("accounts.number"), control: number, help: t("accounts.numberHelp") }),
        storeFullRow,
        el("details.more", {},
          el("summary", {}, t("common.moreDetails")),
          el("div.stack-3", { style: { paddingTop: "16px" } },
            field({ label: "IFSC", control: ifsc }),
          ),
        ),
        field({ label: t("common.whoCanSee"), control: visibility, help: t("accounts.visibility.help") }),
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      labelField.setError("");
      if (!label.value.trim()) { labelField.setError(t("common.giveItAName")); return; }
      try {
        await api.createAccount(state.household.id, {
          label: label.value.trim(),
          accountKind: kind.value,
          institutionId: institution.value || null,
          number: number.value.trim() || null,
          storeFullNumber: storeFull.checked,
          ifsc: ifsc.value.trim() || null,
          visibility: visibility.value,
          holders: [{ memberId: holderId, holderType: "primary" }],
        });
        modal.close();
        toast(t("common.saved"));
        await reload();
      } catch (error) { labelField.setError(error.message); }
    });
    label.focus();
  }

  // -------------------------------------------------------------- detail ---

  async function openDetail(id) {
    const body = el("div.stack-3", {}, skeletonRows(2));
    const modal = sheet({ title: t("accounts.detailTitle"), body });
    const row = await api.account(state.household.id, id);

    const numberLine = el("div", { style: { fontFamily: "var(--font-mono)" } },
      row.numberMasked || t("accounts.noNumber"));

    const draw = () => mount(body, el("div.stack-3", {},
      el("div", {},
        el("h3", {}, row.label),
        el("div.caption.muted", {},
          [kindLabel(row.accountKind), row.institutionName]
            .filter(Boolean).join(" · ")),
      ),

      el("div.card.card-tight", {},
        el("div.row-between", {},
          el("div", {}, el("div.overline", {}, t("accounts.numberShort")), numberLine),
          row.hasFullNumber && el("button.btn.btn-sm", {
            type: "button", onclick: (e) => reveal(e.currentTarget),
          }, t("accounts.showFull")),
        ),
        !row.hasFullNumber && el("div.caption.muted", { style: { marginTop: "8px" } },
          t("accounts.lastFourOnly")),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, t("accounts.holdersPrivacy")),
        ...row.holders.map((h) => detailRow(h.name, t(h.holderType === "joint" ? "accounts.holder.joint" : "accounts.holder.primary"))),
        detailRow(t("common.whoCanSeeIt"), row.visibility === "household"
          ? t("common.everyoneIn", { name: state.household.name })
          : row.visibility === "scoped" ? t("common.specificPeople") : t("accounts.visibility.holdersOnly")),
        detailRow(t("accounts.holdingsLinked"), String(row.linkedInvestmentCount)),
        row.ifsc && detailRow("IFSC", row.ifsc),
      ),

      // A locker is the canonical case: which branch, and who has the key.
      whereWhoCard("account", id),
      sealedNoteCard("account", id),
    ));

    /**
     * Confirms who you are first, then shows the number — and only for as long
     * as the sheet is open. It is never written into the list or cached.
     */
    async function reveal(button) {
      await withBusy(button, async () => {
        try {
          const { elevated } = await api.stepUpStatus();
          if (!elevated) { await confirmIdentity(button); return; }
          const { number } = await api.revealNumber(state.household.id, id);
          show(number, button);
        } catch (error) {
          if (error.code === "step_up_required") { await confirmIdentity(button); return; }
          toast(error.message, { tone: "error" });
        }
      });
    }

    function show(number, button) {
      numberLine.textContent = number;
      // The button goes: it has done its job, and leaving it there invites a
      // second reveal that would write another audit entry for no new reason.
      button?.remove();
      toast(t("accounts.shown"));
    }

    async function confirmIdentity(button) {
      const challenge = await api.stepUpRequest();
      const code = textInput({
        class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
        maxLength: 6, placeholder: "······", "aria-label": t("common.sixDigitCode"),
      });
      const codeField = field({
        // Where it went depends on how this account signs in; older servers do not say.
        label: challenge.channel === "email" ? t("common.codeEmailed") : t("common.codeTexted"),
        control: code, required: true,
        help: t("accounts.revealHelp"),
      });
      const confirm = el("button.btn.btn-primary", { type: "button" }, t("stepUp.confirm"));
      const inner = sheet({
        title: t("stepUp.title"),
        body: el("div.stack-3", {},
          codeField,
          challenge.developmentCode && el("div.banner.banner-accent", {},
            el("div", {}, t("stepUp.developmentCode", { code: challenge.developmentCode })),
          ),
        ),
        footer: [confirm],
      });
      confirm.onclick = () => withBusy(confirm, async () => {
        try {
          await api.stepUpVerify({ code: code.value.trim(), requestId: challenge.requestId });
          const { number } = await api.revealNumber(state.household.id, id);
          inner.close();
          show(number, button);
        } catch (error) { codeField.setError(error.message); }
      });
      code.focus();
    }

    draw();
  }
}

function detailRow(label, value) {
  if (!value) return null;
  return el("div.row-between", { style: { fontSize: "var(--text-sm)" } },
    el("span.muted", {}, label), el("span", { style: { textAlign: "right" } }, value));
}
