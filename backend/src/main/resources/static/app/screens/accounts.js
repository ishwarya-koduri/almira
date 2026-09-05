/* Institutions and accounts — "which bank funds which SIP" (docs/01 §4).

   Note what this screen does NOT show by default: a full account number. Only
   the last four digits are kept unless someone explicitly asks otherwise, and
   even then seeing the rest takes a fresh confirmation. */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, select, skeletonRows, empty,
  withBusy, toast,
} from "../ui.js";
import { state, myMember } from "../state.js";
import { reload } from "../app.js";

const KINDS = [
  { value: "savings", label: "Savings account" },
  { value: "current", label: "Current account" },
  { value: "demat", label: "Demat account" },
  { value: "folio", label: "Mutual fund folio" },
  { value: "wallet", label: "Wallet" },
  { value: "locker", label: "Bank locker" },
  { value: "other", label: "Something else" },
];
const KIND_LABEL = Object.fromEntries(KINDS.map((k) => [k.value, k.label]));

export async function accountsScreen(host) {
  mount(host, el("div.stack", {}, skeletonRows(3)));
  const rows = await api.accounts(state.household.id);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("h1", {}, "Accounts"),
      el("button.btn.btn-primary", { type: "button", onclick: () => openForm() }, "＋ Add an account"),
    ),
    el("p.muted", { style: { marginTop: "-12px" } },
      "Where things are held, so you know what funds what."),

    rows.length === 0
      ? el("div.card", {}, empty({
          title: "No accounts yet",
          body: "Add the bank accounts, demat accounts and folios your holdings sit in.",
          action: el("button.btn.btn-primary", { onclick: () => openForm() }, "＋ Add an account"),
        }))
      : el("div.card.card-tight", {},
          el("div.list", {}, ...rows.map((row) => el("button.list-row", {
            type: "button", onclick: () => openDetail(row.id),
          },
            el("div.grow", { style: { minWidth: 0 } },
              el("div.title", {}, row.label),
              el("div.meta", {}, [
                KIND_LABEL[row.accountKind] || row.accountKind,
                row.institutionName,
                row.numberMasked,
                row.holders.map((h) => h.name).filter(Boolean).join(" & "),
              ].filter(Boolean).join(" · ")),
            ),
            el("div.amount", {},
              el("div.meta", {},
                row.linkedInvestmentCount === 0
                  ? "nothing linked"
                  : `${row.linkedInvestmentCount} linked`),
            ),
            row.hasFullNumber && el("span.pill.pill-accent", { title: "Full number stored, encrypted" }, "Full"),
          ))),
        ),
  ));

  // ---------------------------------------------------------------- form ---

  function openForm() {
    const label = textInput({ placeholder: "SBI savings — salary", "aria-label": "Name" });
    const kind = select({ options: KINDS, value: "savings", "aria-label": "Kind" });
    const number = textInput({ placeholder: "Account number", "aria-label": "Account number" });
    const ifsc = textInput({ placeholder: "SBIN0001234", "aria-label": "IFSC" });

    const storeFull = el("input", { type: "checkbox" });
    const storeFullRow = el("label.row", { style: { alignItems: "flex-start", gap: "8px" } },
      storeFull,
      el("div", {},
        el("div", { style: { fontSize: "var(--text-sm)" } }, "Keep the whole number"),
        el("div.caption.muted", {},
          "Off by default. We keep only the last four digits — enough to recognise " +
          "the account. Turn this on and the rest is stored encrypted, and seeing " +
          "it later needs a fresh confirmation."),
      ),
    );

    const visibility = select({
      options: [
        { value: "private", label: "Private — only its holders" },
        { value: "household", label: `Shared with ${state.household.name}` },
      ],
      value: state.user?.defaultVisibility || state.household.defaultVisibility,
      "aria-label": "Who can see this",
    });

    const institution = select({
      options: [{ value: "", label: "Not linked to an institution" }],
      "aria-label": "Institution",
    });
    api.institutions(state.household.id).then((list) => {
      list.forEach((i) => institution.append(el("option", { value: i.id }, i.name)));
    }).catch(() => { /* optional */ });

    const labelField = field({ label: "What should we call it?", control: label, required: true });
    const save = el("button.btn.btn-primary.grow", { type: "button" }, "Save");

    const modal = sheet({
      title: "Add an account",
      body: el("div.stack-3", {},
        labelField,
        field({ label: "What kind?", control: kind }),
        field({ label: "Which bank or fund house?", control: institution }),
        field({ label: "Account number", control: number,
          help: "We'll keep only the last four digits unless you say otherwise." }),
        storeFullRow,
        el("details.more", {},
          el("summary", {}, "More details"),
          el("div.stack-3", { style: { paddingTop: "16px" } },
            field({ label: "IFSC", control: ifsc }),
          ),
        ),
        field({ label: "Who can see this?", control: visibility,
          help: "Private means only its holders — not even a household admin." }),
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      labelField.setError("");
      if (!label.value.trim()) { labelField.setError("Give it a name"); return; }
      try {
        await api.createAccount(state.household.id, {
          label: label.value.trim(),
          accountKind: kind.value,
          institutionId: institution.value || null,
          number: number.value.trim() || null,
          storeFullNumber: storeFull.checked,
          ifsc: ifsc.value.trim() || null,
          visibility: visibility.value,
          holders: [{ memberId: myMember()?.id, holderType: "primary" }],
        });
        modal.close();
        toast("Saved.");
        await reload();
      } catch (error) { labelField.setError(error.message); }
    });
    label.focus();
  }

  // -------------------------------------------------------------- detail ---

  async function openDetail(id) {
    const body = el("div.stack-3", {}, skeletonRows(2));
    const modal = sheet({ title: "Account", body });
    const row = await api.account(state.household.id, id);

    const numberLine = el("div", { style: { fontFamily: "var(--font-mono)" } },
      row.numberMasked || "No number recorded");

    const draw = () => mount(body, el("div.stack-3", {},
      el("div", {},
        el("h3", {}, row.label),
        el("div.caption.muted", {},
          [KIND_LABEL[row.accountKind] || row.accountKind, row.institutionName]
            .filter(Boolean).join(" · ")),
      ),

      el("div.card.card-tight", {},
        el("div.row-between", {},
          el("div", {}, el("div.overline", {}, "Number"), numberLine),
          row.hasFullNumber && el("button.btn.btn-sm", {
            type: "button", onclick: (e) => reveal(e.currentTarget),
          }, "Show full number"),
        ),
        !row.hasFullNumber && el("div.caption.muted", { style: { marginTop: "8px" } },
          "Only the last four digits are saved for this account."),
      ),

      el("div.card.card-tight.stack-2", {},
        el("div.overline", {}, "Holders & privacy"),
        ...row.holders.map((h) => detailRow(h.name, h.holderType === "joint" ? "joint" : "primary")),
        detailRow("Who can see it", row.visibility === "household"
          ? `Everyone in ${state.household.name}`
          : row.visibility === "scoped" ? "Specific people" : "Only its holders"),
        detailRow("Holdings linked", String(row.linkedInvestmentCount)),
        row.ifsc && detailRow("IFSC", row.ifsc),
      ),
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
      toast("Shown. This isn't saved anywhere on this device.");
    }

    async function confirmIdentity(button) {
      const challenge = await api.stepUpRequest();
      const code = textInput({
        class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
        maxLength: 6, placeholder: "······", "aria-label": "6-digit code",
      });
      const codeField = field({
        label: "Enter the code we texted you", control: code, required: true,
        help: "Just to be sure it's you before we show the full number.",
      });
      const confirm = el("button.btn.btn-primary", { type: "button" }, "Confirm");
      const inner = sheet({
        title: "Confirm it's you",
        body: el("div.stack-3", {},
          codeField,
          challenge.developmentCode && el("div.banner.banner-accent", {},
            el("div", {}, el("b", {}, "Development mode — "), "the code is ",
              el("b", {}, challenge.developmentCode), "."),
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
