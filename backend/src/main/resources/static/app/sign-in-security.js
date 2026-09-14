/* =============================================================================
   How you sign in: second factors, confirming it's you, and the settings card.

   Three things live here because all three screens need them:

     · passkey ceremonies — the server hands over WebAuthn options as JSON with
       base64url fields; the browser wants ArrayBuffers, and answers with
       ArrayBuffers the server wants back as base64url (PasskeyService).
     · confirmItsYou — the sheet that elevates this session: a code to the
       phone or email, or, when the account has one, the authenticator app, a
       passkey or a recovery code. When the server says a code is not enough
       (details.requires = "second_factor"), only the factors are offered.
     · howYouSignInCard — the settings card. It asks for at least two ways in
       (T-05): one lost phone should cost a person one way in, not the account.

   Notices are muted text with an info mark, not coloured boxes. One primary
   action per sheet. Every control is a full-height button.
   ============================================================================= */

import { api, deviceName } from "./api.js";
import { el, mount, sheet, field, textInput, toast, withBusy, formatDate } from "./ui.js";
import { t } from "./i18n.js";

/* --- passkeys ---------------------------------------------------------------- */

export function passkeysSupported() {
  return typeof window !== "undefined" && typeof window.PublicKeyCredential === "function"
    && Boolean(navigator.credentials?.create);
}

function fromB64url(value) {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  return bytes.buffer;
}

function toB64url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function withBuffers(list) {
  return (list || []).map((item) => ({ ...item, id: fromB64url(item.id) }));
}

/** navigator.credentials.create for a ceremony from POST /auth/passkeys/options. */
export async function createPasskey(ceremony) {
  const pk = ceremony.options.publicKey;
  const credential = await navigator.credentials.create({
    publicKey: {
      ...pk,
      challenge: fromB64url(pk.challenge),
      user: { ...pk.user, id: fromB64url(pk.user.id) },
      excludeCredentials: withBuffers(pk.excludeCredentials),
    },
  });
  return {
    type: credential.type,
    id: credential.id,
    rawId: toB64url(credential.rawId),
    response: {
      clientDataJSON: toB64url(credential.response.clientDataJSON),
      attestationObject: toB64url(credential.response.attestationObject),
      transports: credential.response.getTransports?.() || [],
    },
    clientExtensionResults: credential.getClientExtensionResults?.() || {},
  };
}

/** navigator.credentials.get for a ceremony from a passkey/options endpoint. */
export async function usePasskey(ceremony) {
  const pk = ceremony.options.publicKey;
  const credential = await navigator.credentials.get({
    publicKey: { ...pk, challenge: fromB64url(pk.challenge), allowCredentials: withBuffers(pk.allowCredentials) },
  });
  const response = credential.response;
  return {
    type: credential.type,
    id: credential.id,
    rawId: toB64url(credential.rawId),
    response: {
      clientDataJSON: toB64url(response.clientDataJSON),
      authenticatorData: toB64url(response.authenticatorData),
      signature: toB64url(response.signature),
      ...(response.userHandle ? { userHandle: toB64url(response.userHandle) } : {}),
    },
    clientExtensionResults: credential.getClientExtensionResults?.() || {},
  };
}

/** A person closing the browser's passkey prompt is not an error worth a red sentence. */
function passkeyCancelled(error) {
  return error?.name === "NotAllowedError" || error?.name === "AbortError";
}

/* --- small pieces ------------------------------------------------------------ */

/** A calm notice: muted words with an info mark. */
export function notice(text, props = {}) {
  return el("p.muted.notice", { style: { margin: 0 }, ...props },
    el("span", { "aria-hidden": "true" }, "ⓘ "), text);
}

function codeInput(props = {}) {
  return textInput({
    class: "otp-input", inputMode: "numeric", autocomplete: "one-time-code",
    maxLength: 6, placeholder: "······", "aria-label": t("signin.authenticator.aria"), ...props,
  });
}

function recoveryInput() {
  return textInput({
    autocomplete: "off", autocapitalize: "characters", spellcheck: false,
    placeholder: "XXXX-XXXX-XXXX", "aria-label": t("signin.recovery.aria"),
    style: { fontFamily: "var(--font-mono)" },
  });
}

/* --- confirming it's you ---------------------------------------------------------- */

/**
 * Runs [action]; if the server wants this session confirmed first, asks, then
 * runs it once more. Resolves to the action's result, or undefined when the
 * person closed the sheet.
 */
export async function withConfirmation(action) {
  try {
    return await action();
  } catch (error) {
    if (error.code !== "step_up_required") throw error;
    const confirmed = await confirmItsYou({
      reason: error.message,
      secondFactorOnly: error.details?.requires === "second_factor",
    });
    return confirmed ? action() : undefined;
  }
}

/** The sheet. Resolves true once this session is confirmed, false if closed. */
export function confirmItsYou({ reason = "", secondFactorOnly = false } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const body = el("div.stack-3", {}, el("p.muted", {}, t("app.loading")));
    const footer = el("div.row", { style: { width: "100%" } });
    const modal = sheet({
      title: t("signin.confirm.title"),
      body,
      footer,
      onClose: () => { if (!done) resolve(false); },
    });
    const finish = () => { done = true; modal.close(); resolve(true); };

    api.signInMethods().catch(() => null).then((methods) => {
      const ways = [];
      if (!secondFactorOnly) ways.push("code");
      if (methods?.authenticator) ways.push("authenticator");
      if (methods?.passkeys?.length && passkeysSupported()) ways.push("passkey");
      if (methods?.recoveryCodesLeft > 0) ways.push("recovery");
      if (!ways.length) ways.push("code");
      show(ways[0], ways);
    });

    function show(way, ways) {
      const others = ways.filter((w) => w !== way).map((w) => el("button.btn.btn-ghost.btn-block", {
        type: "button", onclick: () => show(w, ways),
      }, t(`signin.confirm.use.${w}`)));
      const intro = reason ? notice(reason) : null;
      const error = el("p.help.error", { role: "alert", style: { margin: 0, minHeight: "1.2em" } });
      const primary = el("button.btn.btn-primary.btn-block", { type: "button" }, t("signin.confirm.button"));
      mount(footer, primary);

      if (way === "code") {
        const input = codeInput({ "aria-label": t("auth.code.aria") });
        const holder = el("div.stack-2", {}, el("p.muted", {}, t("auth.code.sending")));
        let challenge = null;
        api.stepUpRequest().then((c) => {
          challenge = c;
          mount(holder,
            field({
              label: t(c.channel === "email" ? "signin.confirm.code.email" : "signin.confirm.code.phone"),
              control: input, required: true,
            }),
            c.developmentCode && notice(`${t("auth.dev.lead")}${t("auth.dev.body")}${c.developmentCode}.`),
          );
          input.focus();
        }).catch((e) => { mount(holder, notice(e.message, { role: "alert" })); });
        primary.onclick = () => withBusy(primary, async () => {
          error.textContent = "";
          if (!challenge) return;
          try {
            await api.stepUpVerify({ code: input.value.trim(), requestId: challenge.requestId });
            finish();
          } catch (e) { error.textContent = e.message; input.select(); }
        });
        mount(body, intro, holder, error, ...others);
        return;
      }

      if (way === "authenticator" || way === "recovery") {
        const input = way === "authenticator" ? codeInput() : recoveryInput();
        const submit = () => withBusy(primary, async () => {
          error.textContent = "";
          try {
            if (way === "authenticator") await api.stepUpAuthenticator(input.value.trim());
            else await api.stepUpRecoveryCode(input.value.trim());
            finish();
          } catch (e) { error.textContent = e.message; input.select(); }
        });
        primary.onclick = submit;
        if (way === "authenticator") {
          input.addEventListener("input", () => {
            input.value = input.value.replace(/\D/g, "");
            if (input.value.length === 6) submit();
          });
        }
        mount(body, intro,
          field({ label: t(`signin.confirm.${way}.label`), control: input, required: true,
            help: t(`signin.confirm.${way}.help`) }),
          error, ...others);
        input.focus();
        return;
      }

      // Passkey: one tap on the button, then the device's own prompt.
      primary.textContent = t("signin.passkey.use");
      primary.onclick = () => withBusy(primary, async () => {
        error.textContent = "";
        try {
          const ceremony = await api.stepUpPasskeyOptions();
          const credential = await usePasskey(ceremony);
          await api.stepUpPasskey(ceremony.requestId, credential);
          finish();
        } catch (e) {
          error.textContent = passkeyCancelled(e) ? t("signin.passkey.cancelled") : e.message;
        }
      });
      mount(body, intro, notice(t("signin.passkey.explain")), error, ...others);
    }
  });
}

/* --- the settings card ------------------------------------------------------------ */

/**
 * "How you sign in". [rerender] draws the settings screen again after a change.
 */
export async function howYouSignInCard(rerender) {
  const methods = await api.signInMethods();
  const canAddPasskey = methods.passkeysAvailable && passkeysSupported();

  // Exactly one primary action on the card: the next way in to add, while there
  // are fewer than two.
  const nextToAdd = methods.meetsMinimum ? null : canAddPasskey ? "passkey" : "authenticator";
  const button = (label, onclick, primary = false, extra = {}) =>
    el(`button.btn${primary ? ".btn-primary" : ""}`, { type: "button", onclick, ...extra }, label);

  const rows = [];
  if (methods.phone) {
    rows.push(row(t("signin.card.phone"), methods.phone,
      methods.phoneSignIn ? t("signin.card.canSignIn") : t("signin.card.notForSignIn"),
      button(t("signin.card.changePhone"), () => changePhone(rerender), false, { "data-change-phone": "" })));
  } else {
    rows.push(row(t("signin.card.phone"), t("signin.card.none"), "",
      button(t("signin.card.addPhone"), () => changePhone(rerender), false, { "data-change-phone": "" })));
  }
  if (methods.email) {
    rows.push(row(t("signin.card.email"), methods.email,
      methods.emailSignIn ? t("signin.card.canSignIn") : t("signin.card.notForSignIn"), null));
  }
  rows.push(methods.authenticator
    ? row(t("signin.card.authenticator"), t("signin.card.on"), "",
        button(t("app.remove"), (e) => removeAuthenticator(e.currentTarget, rerender)))
    : row(t("signin.card.authenticator"), t("signin.card.notSetUp"), t("signin.card.authenticatorHelp"),
        button(t("signin.card.setUp"), () => addAuthenticator(rerender), nextToAdd === "authenticator",
          { "data-add-authenticator": "" })));
  methods.passkeys.forEach((passkey) => rows.push(row(
    t("signin.card.passkey"), passkey.name,
    passkey.lastUsedAt ? t("signin.card.lastUsed", { date: formatDate(passkey.lastUsedAt) })
      : t("signin.card.added", { date: formatDate(passkey.createdAt) }),
    button(t("app.remove"), (e) => removePasskey(e.currentTarget, passkey, rerender)),
  )));
  if (canAddPasskey) {
    rows.push(row(t("signin.card.passkey"), methods.passkeys.length ? t("signin.card.anotherPasskey") : t("signin.card.notSetUp"),
      t("signin.card.passkeyHelp"),
      button(t("signin.card.addPasskey"), () => addPasskey(rerender), nextToAdd === "passkey", { "data-add-passkey": "" })));
  }
  if (methods.authenticator || methods.passkeys.length) {
    rows.push(row(t("signin.card.recovery"), t("signin.card.recoveryLeft", { count: methods.recoveryCodesLeft }),
      t("signin.card.recoveryHelp"),
      button(t("signin.card.newCodes"), (e) => replaceCodes(e.currentTarget))));
  }

  return el("div.card.stack-3", { "data-how-you-sign-in": "" },
    el("h4", {}, t("signin.card.title")),
    methods.meetsMinimum
      ? el("p.muted", { style: { margin: 0 } }, t("signin.card.enough", { count: methods.count }))
      : notice(t("signin.card.addSecond"), { "data-needs-second-way": "" }),
    el("div.list", {}, ...rows),
  );
}

function row(title, value, meta, action) {
  return el("div.list-row.wrap", { style: { cursor: "default", gap: "var(--space-3)" } },
    el("div.grow", { style: { minWidth: "12rem" } },
      el("div.title", {}, title),
      el("div", {}, value),
      meta && el("div.muted", {}, meta),
    ),
    action,
  );
}

async function addAuthenticator(rerender) {
  let setup;
  try {
    setup = await withConfirmation(() => api.beginAuthenticator());
  } catch (error) { toast(error.message, { tone: "error" }); return; }
  if (!setup) return;

  const input = codeInput();
  const codeField = field({ label: t("signin.authenticator.enterCode"), control: input, required: true });
  const confirm = el("button.btn.btn-primary.btn-block", { type: "button" }, t("signin.authenticator.finish"));
  const grouped = setup.secret.match(/.{1,4}/g).join(" ");
  const modal = sheet({
    title: t("signin.authenticator.title"),
    body: el("div.stack-3", {},
      el("p", { style: { margin: 0 } }, t("signin.authenticator.step1")),
      // On a phone this opens the authenticator app with everything filled in.
      el("a.btn.btn-block", { href: setup.otpauthUri }, t("signin.authenticator.open")),
      el("p.muted", { style: { margin: 0 } }, t("signin.authenticator.orType")),
      el("code", { style: { fontSize: "1.05rem", letterSpacing: "0.08em", wordBreak: "break-all" }, "data-secret": "" }, grouped),
      el("p", { style: { margin: 0 } }, t("signin.authenticator.step2")),
      codeField,
    ),
    footer: [confirm],
  });
  const submit = () => withBusy(confirm, async () => {
    codeField.setError("");
    try {
      const { recoveryCodes } = await api.confirmAuthenticator(input.value.trim());
      modal.close();
      toast(t("signin.authenticator.done"));
      if (recoveryCodes.length) showRecoveryCodes(recoveryCodes, rerender); else rerender();
    } catch (error) { codeField.setError(error.message); input.select(); }
  });
  confirm.onclick = submit;
  input.addEventListener("input", () => {
    input.value = input.value.replace(/\D/g, "");
    if (input.value.length === 6) submit();
  });
}

async function removeAuthenticator(button, rerender) {
  await withBusy(button, async () => {
    try {
      const removed = await withConfirmation(() => api.removeAuthenticator().then(() => true));
      if (removed) { toast(t("signin.authenticator.removed")); rerender(); }
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}

async function addPasskey(rerender) {
  try {
    const ceremony = await withConfirmation(() => api.passkeyOptions());
    if (!ceremony) return;
    const credential = await createPasskey(ceremony);
    const added = await api.addPasskey({ requestId: ceremony.requestId, credential, name: deviceName() || "Passkey" });
    toast(t("signin.passkey.added"));
    if (added.recoveryCodes.length) showRecoveryCodes(added.recoveryCodes, rerender); else rerender();
  } catch (error) {
    if (passkeyCancelled(error)) return;
    toast(error.message, { tone: "error" });
  }
}

async function removePasskey(button, passkey, rerender) {
  await withBusy(button, async () => {
    try {
      const removed = await withConfirmation(() => api.removePasskey(passkey.id).then(() => true));
      if (removed) { toast(t("signin.passkey.removed", { name: passkey.name })); rerender(); }
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}

async function replaceCodes(button) {
  await withBusy(button, async () => {
    try {
      const result = await withConfirmation(() => api.replaceRecoveryCodes());
      if (result) showRecoveryCodes(result.recoveryCodes, null);
    } catch (error) { toast(error.message, { tone: "error" }); }
  });
}

/**
 * Shown once. The page they are on is the only copy: the server keeps a hash.
 * Laid out to be printed or written on paper and kept with the family papers.
 */
function showRecoveryCodes(codes, rerender) {
  const done = el("button.btn.btn-primary.btn-block", { type: "button" }, t("signin.recovery.writtenDown"));
  const text = `${t("signin.recovery.fileTitle")}\n\n${codes.join("\n")}\n\n${t("signin.recovery.fileNote")}\n`;
  const modal = sheet({
    title: t("signin.recovery.title"),
    body: el("div.stack-3", { "data-recovery-codes": "" },
      el("p", { style: { margin: 0 } }, t("signin.recovery.explain")),
      el("ol", {
        style: {
          fontFamily: "var(--font-mono)", fontSize: "1.05rem", display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(11rem, 1fr))", gap: "var(--space-2) var(--space-5)",
          margin: 0, paddingInlineStart: "1.5rem",
        },
      }, ...codes.map((code) => el("li", {}, code))),
      notice(t("signin.recovery.once")),
      el("div.row.wrap", {},
        el("button.btn", {
          type: "button",
          onclick: async () => {
            try { await navigator.clipboard.writeText(text); toast(t("signin.recovery.copied")); }
            catch { toast(t("signin.recovery.copyFailed")); }
          },
        }, t("signin.recovery.copy")),
        el("button.btn", {
          type: "button",
          onclick: () => {
            const link = el("a", {
              href: URL.createObjectURL(new Blob([text], { type: "text/plain" })),
              download: "almira-recovery-codes.txt",
            });
            document.body.append(link);
            link.click();
            setTimeout(() => { URL.revokeObjectURL(link.href); link.remove(); }, 0);
          },
        }, t("signin.recovery.save")),
      ),
    ),
    footer: [done],
    onClose: () => rerender?.(),
  });
  done.onclick = () => modal.close();
}

async function changePhone(rerender) {
  const phone = textInput({ type: "tel", inputMode: "tel", autocomplete: "tel", placeholder: "98765 43210" });
  const phoneField = field({ label: t("signin.phone.new"), control: phone, required: true, help: t("signin.phone.help") });
  const primary = el("button.btn.btn-primary.btn-block", { type: "button" }, t("auth.sendCode"));
  const body = el("div.stack-3", {}, notice(t("signin.phone.explain")), phoneField);
  const modal = sheet({ title: t("signin.phone.title"), body, footer: [primary] });

  primary.onclick = () => withBusy(primary, async () => {
    phoneField.setError("");
    const number = phone.value.trim();
    if (!number) { phoneField.setError(t("auth.phone.missing")); return; }
    try {
      const challenge = await withConfirmation(() => api.requestPhoneChange(number));
      if (!challenge) return;
      showCode(number, challenge);
    } catch (error) { phoneField.setError(error.message); }
  });

  function showCode(number, challenge) {
    const code = codeInput({ "aria-label": t("auth.code.aria") });
    const codeField = field({
      label: t("auth.code.label"), control: code, required: true,
      help: t("auth.code.help", { to: number, minutes: Math.round(challenge.expiresInSeconds / 60) }),
    });
    mount(body, el("p", { style: { margin: 0 } }, t("signin.phone.codeSent")), codeField,
      challenge.developmentCode && notice(`${t("auth.dev.lead")}${t("auth.dev.body")}${challenge.developmentCode}.`));
    primary.textContent = t("signin.phone.confirm");
    const submit = () => withBusy(primary, async () => {
      codeField.setError("");
      try {
        await api.verifyPhoneChange({ phone: number, code: code.value.trim(), requestId: challenge.requestId });
        modal.close();
        toast(t("signin.phone.changed"));
        rerender();
      } catch (error) { codeField.setError(error.message); code.select(); }
    });
    primary.onclick = submit;
    code.addEventListener("input", () => {
      code.value = code.value.replace(/\D/g, "");
      if (code.value.length === 6) submit();
    });
    code.focus();
  }
}

/* --- the second step of a sign-in ------------------------------------------------------ */

/**
 * Drawn by the sign-in screen when a correct code answered
 * second_factor_required. [details] carries the token and the methods.
 */
export function secondFactorStep(host, details, { onSignedIn, onStartAgain }) {
  const methods = details.methods || [];
  const token = details.secondFactorToken;
  const hasPasskey = methods.includes("passkey") && passkeysSupported();
  let way = methods.includes("authenticator") ? "authenticator" : hasPasskey ? "passkey" : "recovery";

  const draw = () => {
    const error = el("p.help.error", { role: "alert", style: { margin: 0, minHeight: "1.2em" } });
    const submit = el("button.btn.btn-primary.btn-block", { type: "submit" }, t("auth.continue"));
    const others = [];
    if (way !== "authenticator" && methods.includes("authenticator")) others.push("authenticator");
    if (way !== "passkey" && hasPasskey) others.push("passkey");
    if (way !== "recovery" && methods.includes("recovery_code")) others.push("recovery");

    const fail = (e) => {
      if (e.code === "second_factor_expired" || e.code === "second_factor_locked") {
        mount(host, el("div.auth-card.card", {}, el("div.stack-3", {},
          el("h1", {}, t("signin.second.title")),
          notice(e.message, { role: "alert" }),
          el("button.btn.btn-primary.btn-block", { type: "button", onclick: onStartAgain }, t("signin.second.startAgain")),
        )));
        return;
      }
      error.textContent = passkeyCancelled(e) ? t("signin.passkey.cancelled") : e.message;
    };

    let control = null;
    let run;
    if (way === "passkey") {
      submit.textContent = t("signin.passkey.use");
      run = async () => {
        const ceremony = await api.secondFactorPasskeyOptions(token);
        const credential = await usePasskey(ceremony);
        await api.completeWithPasskey(token, ceremony.requestId, credential);
      };
    } else {
      const input = way === "authenticator" ? codeInput() : recoveryInput();
      control = field({ label: t(`signin.second.${way}.label`), control: input, required: true,
        help: t(`signin.second.${way}.help`) });
      run = () => (way === "authenticator"
        ? api.completeWithAuthenticator(token, input.value.trim())
        : api.completeWithRecoveryCode(token, input.value.trim()));
      if (way === "authenticator") {
        input.addEventListener("input", () => {
          input.value = input.value.replace(/\D/g, "");
          if (input.value.length === 6) form.requestSubmit();
        });
      }
    }

    const form = el("form.stack-3", {
      onsubmit: (event) => {
        event.preventDefault();
        withBusy(submit, async () => {
          error.textContent = "";
          try { await run(); await onSignedIn(); } catch (e) { fail(e); }
        });
      },
    },
      el("div", {},
        el("h1", {}, t("signin.second.title")),
        el("p.muted", { style: { margin: 0 } }, t(`signin.second.${way}.intro`)),
      ),
      control, error, submit,
      ...others.map((other) => el("button.btn.btn-ghost.btn-block", {
        type: "button", onclick: () => { way = other; draw(); },
      }, t(`signin.confirm.use.${other}`))),
      el("button.btn.btn-ghost.btn-block", { type: "button", onclick: onStartAgain }, t("signin.second.startAgain")),
    );
    mount(host, el("div.auth-card.card", { "data-second-factor": "" }, form));
    host.querySelector("input")?.focus();
  };
  draw();
}
