/* =============================================================================
   "Confirm it's you" — a fresh code before something sensitive.

   The same sheet accounts.js shows before revealing a number, for the actions
   that came after it: naming a nominee, and recording a parent's consent for a
   child. Resolves true once this session is elevated (docs/05 §5), false if the
   person closed the sheet.
   ============================================================================= */

import { api } from "./api.js";
import { el, sheet, field, textInput, withBusy } from "./ui.js";
import { t } from "./i18n.js";

export async function confirmItsYou(reason) {
  const { elevated } = await api.stepUpStatus().catch(() => ({ elevated: false }));
  if (elevated) return true;

  const challenge = await api.stepUpRequest();
  return new Promise((resolve) => {
    let done = false;
    const code = textInput({
      class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
      maxLength: 6, placeholder: "······", "aria-label": t("stepUp.codeLabel"),
    });
    const codeField = field({
      label: challenge.channel === "email" ? t("stepUp.emailed") : t("stepUp.texted"),
      control: code, required: true, help: reason,
    });
    const confirm = el("button.btn.btn-primary", { type: "button" }, t("stepUp.confirm"));
    const modal = sheet({
      title: t("stepUp.title"),
      body: el("div.stack-3", {},
        codeField,
        challenge.developmentCode && el("p.caption.muted", { style: { margin: 0 } },
          t("stepUp.developmentCode", { code: challenge.developmentCode })),
      ),
      footer: [confirm],
      onClose: () => { if (!done) resolve(false); },
    });
    confirm.onclick = () => withBusy(confirm, async () => {
      try {
        await api.stepUpVerify({ code: code.value.trim(), requestId: challenge.requestId });
        done = true;
        modal.close();
        resolve(true);
      } catch (error) { codeField.setError(error.message); }
    });
  });
}
