/* Sign-in: phone, then the code we text. Two steps, nothing else on screen. */

import { api } from "../api.js";
import { el, mount, field, textInput, withBusy, toast } from "../ui.js";

export function authScreen(onSignedIn) {
  const host = el("div.auth", {});
  showPhoneStep(host, onSignedIn);
  return host;
}

function showPhoneStep(host, onSignedIn) {
  const input = textInput({
    type: "tel", inputMode: "tel", autocomplete: "tel", placeholder: "98765 43210",
    "aria-label": "Phone number",
  });
  const phoneField = field({ label: "Your phone number", control: input, required: true,
    help: "We'll text you a 6-digit code." });

  const submit = el("button.btn.btn-primary.btn-block", { type: "submit" }, "Send code");

  const form = el("form.stack-3", {
    onsubmit: async (event) => {
      event.preventDefault();
      phoneField.setError("");
      if (!input.value.trim()) { phoneField.setError("Enter your phone number"); input.focus(); return; }
      await withBusy(submit, async () => {
        try {
          const challenge = await api.requestOtp(input.value);
          showCodeStep(host, input.value.trim(), challenge, onSignedIn);
        } catch (error) {
          phoneField.setError(error.message);
        }
      });
    },
  }, phoneField, submit);

  mount(host, el("div.auth-card.card", {},
    el("div.stack-3", {},
      el("div", {},
        el("h1", {}, "Welcome to Almira"),
        el("p.muted", {}, "Everything your family owns and owes, in one calm, private place."),
      ),
      form,
      el("p.caption.faint", { style: { margin: 0 } },
        "🔒 Encrypted. Almira never asks for a bank password and never moves money."),
    ),
  ));
  input.focus();
}

function showCodeStep(host, phone, challenge, onSignedIn) {
  const input = textInput({
    class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
    maxLength: 6, placeholder: "······", "aria-label": "6-digit code",
  });
  const codeField = field({
    label: "Enter the code", control: input, required: true,
    help: `Sent to ${phone}. It expires in ${Math.round(challenge.expiresInSeconds / 60)} minutes.`,
  });

  const submit = el("button.btn.btn-primary.btn-block", { type: "submit" }, "Continue");

  const verify = async () => {
    codeField.setError("");
    await withBusy(submit, async () => {
      try {
        await api.verifyOtp(phone, input.value.trim(), challenge.requestId);
        await onSignedIn();
      } catch (error) {
        codeField.setError(error.message);
        input.select();
      }
    });
  };

  // Six digits is the whole input, so submitting on the sixth keystroke saves a
  // tap without ever guessing at an incomplete code.
  input.addEventListener("input", () => {
    input.value = input.value.replace(/\D/g, "");
    if (input.value.length === 6) verify();
  });

  const resend = el("button.btn.btn-ghost.btn-block", { type: "button", disabled: true },
    `Resend in ${challenge.resendAfterSeconds}s`);
  let remaining = challenge.resendAfterSeconds;
  const tick = setInterval(() => {
    remaining -= 1;
    if (remaining > 0) { resend.textContent = `Resend in ${remaining}s`; return; }
    clearInterval(tick);
    resend.disabled = false;
    resend.textContent = "Send a new code";
    resend.onclick = async () => {
      try {
        const next = await api.requestOtp(phone);
        showCodeStep(host, phone, next, onSignedIn);
        toast("New code sent.");
      } catch (error) { toast(error.message, { tone: "error" }); }
    };
  }, 1000);

  mount(host, el("div.auth-card.card", {},
    el("form.stack-3", { onsubmit: (e) => { e.preventDefault(); verify(); } },
      el("div", {},
        el("h1", {}, "Check your phone"),
        el("p.muted", {}, "We've sent you a 6-digit code."),
      ),
      codeField,
      submit,
      resend,
      // Development only: the server echoes the code when no SMS provider is
      // configured, so the whole flow is usable without an SMS bill.
      challenge.developmentCode && el("div.banner.banner-accent", {},
        el("div", {},
          el("b", {}, "Development mode — "),
          "no SMS provider configured, so the code is ",
          el("b", {}, challenge.developmentCode), ".",
        ),
      ),
    ),
  ));
  input.focus();
}
