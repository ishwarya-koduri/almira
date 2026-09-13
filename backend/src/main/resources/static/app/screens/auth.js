/* Sign-in: an address, then the code we send to it. Two steps, nothing else on screen.

   The server says which ways in it offers (GET /auth/otp/channels): a phone
   number, an email address, or both. The closed alpha is email alone. When both
   are on, phone comes first and one quiet link switches — never a choice
   presented up front, because most people have one obvious answer.

   The email request is answered the same way whether or not the address may
   sign in, so this screen never learns which it was and never says.

   Anything short of a plain success goes through signInOutcome (auth-outcome.js):
   a delayed code still opens the code step, a refused channel switches to the
   one the server named, and each way of not sending gets its own sentence. */

import { api } from "../api.js";
import { el, mount, field, textInput, withBusy, toast } from "../ui.js";
import { t } from "../i18n.js";
import { signInOutcome } from "../auth-outcome.js";

const CHANNELS = {
  phone: {
    input: { type: "tel", inputMode: "tel", autocomplete: "tel", placeholder: "98765 43210" },
    request: (address) => api.requestOtp(address),
    verify: (address, code, requestId) => api.verifyOtp(address, code, requestId),
  },
  email: {
    input: {
      type: "email", inputMode: "email", autocomplete: "email", placeholder: "you@example.com",
      autocapitalize: "off",
    },
    request: (address) => api.requestEmailOtp(address),
    verify: (address, code, requestId) => api.verifyEmailOtp(address, code, requestId),
  },
};

export function authScreen(onSignedIn) {
  const host = el("div.auth", {});
  mount(host, el("div.auth-card.card", {}, el("p.muted", {}, t("app.loading"))));
  api.signInChannels().then((channels) => showAddressStep(host, channels, channels[0], onSignedIn));
  return host;
}

/** The message for a plain error: our own sentence when there is one, else the server's. */
function messageFor(outcome) {
  return outcome.messageKey ? t(outcome.messageKey) : outcome.message;
}

/**
 * The two outcomes that move to another step: a delayed code opens the code
 * step, a refused channel switches to the one the server named. Returns false
 * for a plain error, which the caller shows where it belongs.
 */
function followOutcome(host, channels, channel, address, outcome, onSignedIn) {
  if (outcome.kind === "code") {
    showCodeStep(host, channels, channel, address, outcome.challenge, onSignedIn);
    return true;
  }
  if (outcome.kind === "switch") {
    showAddressStep(host, outcome.channels, outcome.channel, onSignedIn,
      { notice: t(`auth.switched.${outcome.channel}`) });
    return true;
  }
  return false;
}

function showAddressStep(host, channels, channel, onSignedIn, { prefill = "", notice = "" } = {}) {
  const kind = CHANNELS[channel];
  const input = textInput({ ...kind.input, "aria-label": t(`auth.${channel}.label`), value: prefill });
  const addressField = field({
    label: t(`auth.${channel}.label`), control: input, required: true, help: t(`auth.${channel}.help`),
  });

  const submit = el("button.btn.btn-primary.btn-block", { type: "submit" }, t("auth.sendCode"));
  // Not the address's fault (the provider, or our account): said above the
  // button rather than in red under the field, which would read "fix this".
  const problem = el("div.banner", { role: "alert", "data-auth-problem": "" });
  problem.hidden = true;

  const form = el("form.stack-3", {
    onsubmit: async (event) => {
      event.preventDefault();
      addressField.setError("");
      problem.hidden = true;
      const address = input.value.trim();
      if (!address) { addressField.setError(t(`auth.${channel}.missing`)); input.focus(); return; }
      await withBusy(submit, async () => {
        try {
          const challenge = await kind.request(address);
          showCodeStep(host, channels, channel, address, challenge, onSignedIn);
        } catch (error) {
          const outcome = signInOutcome(error, channel);
          if (followOutcome(host, channels, channel, address, outcome, onSignedIn)) return;
          if (outcome.onField) { addressField.setError(messageFor(outcome)); return; }
          problem.textContent = messageFor(outcome);
          problem.hidden = false;
        }
      });
    },
  }, addressField, problem, submit);

  // Only when the server offers the other one too.
  const other = channels.find((c) => c !== channel);
  const switcher = other && el("button.btn.btn-ghost.btn-block", {
    type: "button",
    "data-switch-to": other,
    onclick: () => showAddressStep(host, channels, other, onSignedIn),
  }, t(other === "email" ? "auth.useEmail" : "auth.usePhone"));

  mount(host, el("div.auth-card.card", {},
    el("div.stack-3", {},
      el("div", {},
        el("h1", {}, t("auth.welcome")),
        el("p.muted", {}, t("auth.tagline")),
      ),
      notice ? el("div.banner.banner-accent", { role: "status", "data-auth-notice": "" }, notice) : null,
      form,
      switcher,
      el("p.caption.faint", { style: { margin: 0 } }, t("auth.reassurance")),
    ),
  ));
  input.focus();
}

function showCodeStep(host, channels, channel, address, challenge, onSignedIn) {
  const kind = CHANNELS[channel];
  const input = textInput({
    class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
    maxLength: 6, placeholder: "······", "aria-label": t("auth.code.aria"),
  });
  const codeField = field({
    label: t("auth.code.label"), control: input, required: true,
    help: t("auth.code.help", { to: address, minutes: Math.round(challenge.expiresInSeconds / 60) }),
  });

  const submit = el("button.btn.btn-primary.btn-block", { type: "submit" }, t("auth.continue"));
  let tick = null;

  const verify = async () => {
    codeField.setError("");
    await withBusy(submit, async () => {
      try {
        await kind.verify(address, input.value.trim(), challenge.requestId);
        await onSignedIn();
      } catch (error) {
        // The verify endpoints refuse a disabled channel too, before the code
        // is looked at: no code typed here can ever work, so move.
        const outcome = signInOutcome(error, channel);
        if (outcome.kind === "switch") {
          clearInterval(tick);
          followOutcome(host, channels, channel, address, outcome, onSignedIn);
          return;
        }
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

  const resend = el("button.btn.btn-ghost.btn-block", { type: "button", disabled: true, "data-resend": "" },
    t("auth.resendIn", { seconds: challenge.resendAfterSeconds }));
  let remaining = challenge.resendAfterSeconds;
  const enableResend = () => {
    resend.disabled = false;
    resend.textContent = t("auth.resend");
    resend.onclick = async () => {
      try {
        const next = await kind.request(address);
        showCodeStep(host, channels, channel, address, next, onSignedIn);
        toast(t("auth.resent"));
      } catch (error) {
        const outcome = signInOutcome(error, channel);
        if (followOutcome(host, channels, channel, address, outcome, onSignedIn)) return;
        toast(messageFor(outcome), { tone: "error" });
      }
    };
  };
  if (remaining > 0) {
    tick = setInterval(() => {
      if (!resend.isConnected) { clearInterval(tick); return; }
      remaining -= 1;
      if (remaining > 0) { resend.textContent = t("auth.resendIn", { seconds: remaining }); return; }
      clearInterval(tick);
      enableResend();
    }, 1000);
  } else {
    enableResend();
  }

  mount(host, el("div.auth-card.card", {},
    el("form.stack-3", { onsubmit: (e) => { e.preventDefault(); verify(); } },
      el("div", {},
        el("h1", {}, t(`auth.${channel}.check`)),
        // A delayed send is still a live challenge: say so plainly instead of
        // claiming it was sent, and point at the resend timer below.
        challenge.delayed
          ? el("div.banner", { role: "status", "data-auth-delayed": "" }, t(`auth.code.delayed.${channel}`))
          : el("p.muted", {}, t("auth.code.sent")),
        channel === "email" && el("p.caption.faint", { style: { margin: 0 } }, t("auth.email.spam")),
      ),
      codeField,
      submit,
      resend,
      // A mistyped address should cost a tap, not a reload.
      el("button.btn.btn-ghost.btn-block", {
        type: "button",
        onclick: () => {
          clearInterval(tick);
          showAddressStep(host, channels, channel, onSignedIn, { prefill: address });
        },
      }, t(`auth.${channel}.change`)),
      // Development only: the server echoes the code when nothing can really be
      // sent from it, so the whole flow is usable with no provider at all.
      challenge.developmentCode && el("div.banner.banner-accent", {},
        el("div", {},
          el("b", {}, t("auth.dev.lead")),
          t("auth.dev.body"),
          el("b", {}, challenge.developmentCode), ".",
        ),
      ),
    ),
  ));
  input.focus();
}
