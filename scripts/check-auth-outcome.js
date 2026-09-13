/* =============================================================================
   What sign-in does with an answer that is not a plain success, without a browser.

   The provider faults behind these answers (SandboxFaults) deliberately have no
   HTTP surface, so the web client's handling is asserted here against the
   error shapes OtpService and SignInChannels produce. The rule lives in
   static/app/auth-outcome.js; screens/auth.js only follows it.

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-auth-outcome.js
       # or: node scripts/check-auth-outcome.js  (as an ES module)
   ============================================================================= */

import { signInOutcome, usableChannels, deliveryOutcome, deliveryWhenAskingStops } from "../backend/src/main/resources/static/app/auth-outcome.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}
const apiError = (status, code, message, details) => ({ status, code, message, details: details || {} });

// The exact shapes OtpService.sendFailed and SignInChannels answer with.
const delayed = apiError(504, "otp_delivery_delayed",
  "Your code is taking longer than usual to send. If it arrives, it will work. If it doesn't, you can ask for a new one now.",
  // resendAfterSeconds is 0: one attempt, the cooldown lifted, resend open at once.
  { requestId: "d6386b03-0000-4000-8000-000000000001", expiresInSeconds: 300, resendAfterSeconds: 0 });

for (const channel of ["phone", "email"]) {
  expect(`${channel}: a delayed send opens the code step with the kept challenge`,
    signInOutcome(delayed, channel),
    { kind: "code", challenge: {
      requestId: "d6386b03-0000-4000-8000-000000000001", expiresInSeconds: 300, resendAfterSeconds: 0,
      channel, delayed: true } });

  expect(`${channel}: otp_delivery_failed is its own sentence, on the field, no code step`,
    signInOutcome(apiError(422, "otp_delivery_failed", "x"), channel),
    { kind: "error", messageKey: `auth.error.deliveryFailed.${channel}`, onField: true, message: "x" });
  expect(`${channel}: otp_provider_unavailable is its own sentence, not on the field`,
    signInOutcome(apiError(503, "otp_provider_unavailable", "y"), channel),
    { kind: "error", messageKey: `auth.error.providerUnavailable.${channel}`, onField: false, message: "y" });
  expect(`${channel}: otp_service_unavailable is its own sentence, not on the field`,
    signInOutcome(apiError(503, "otp_service_unavailable", "z"), channel),
    { kind: "error", messageKey: `auth.error.serviceUnavailable.${channel}`, onField: false, message: "z" });
}

expect("a 504 with no challenge in it stays an error (nothing to verify against)",
  signInOutcome(apiError(504, "otp_delivery_delayed", "late", {}), "phone"),
  { kind: "error", messageKey: null, onField: true, message: "late" });
expect("a 504 whose requestId is not a string stays an error",
  signInOutcome(apiError(504, "otp_delivery_delayed", "late",
    { requestId: 7, expiresInSeconds: 300, resendAfterSeconds: 30 }), "phone").kind,
  "error");
expect("another code's 504 does not open the code step",
  signInOutcome(apiError(504, "unknown", "gateway", delayed.details), "phone").kind,
  "error");

// GET /auth/otp/channels failed, so the screen guessed phone; the server says email.
expect("a refused phone switches to the channel the server named",
  signInOutcome(apiError(403, "sign_in_channel_disabled", "off",
    { channel: "phone", enabledChannels: ["email"] }), "phone"),
  { kind: "switch", channels: ["email"], channel: "email" });
expect("a refused email switches to phone when that is what is on",
  signInOutcome(apiError(403, "sign_in_channel_disabled", "off",
    { channel: "email", enabledChannels: ["phone"] }), "email"),
  { kind: "switch", channels: ["phone"], channel: "phone" });
expect("channels this client cannot draw are dropped from what the server named",
  signInOutcome(apiError(403, "sign_in_channel_disabled", "off",
    { channel: "phone", enabledChannels: ["email", "carrier-pigeon"] }), "phone"),
  { kind: "switch", channels: ["email"], channel: "email" });
expect("a 403 naming nothing this client can draw stays an error",
  signInOutcome(apiError(403, "sign_in_channel_disabled", "off",
    { channel: "phone", enabledChannels: [] }), "phone"),
  { kind: "error", messageKey: null, onField: true, message: "off" });
expect("a 403 that still lists this channel does not loop back to it",
  signInOutcome(apiError(403, "sign_in_channel_disabled", "off",
    { channel: "phone", enabledChannels: ["phone"] }), "phone").kind,
  "error");

expect("any other error shows the server's message on the field",
  signInOutcome(apiError(429, "otp_rate_limited", "Wait a moment."), "phone"),
  { kind: "error", messageKey: null, onField: true, message: "Wait a moment." });
expect("usableChannels drops unknowns and duplicates", usableChannels(["email", "fax", "email", "phone"]), ["email", "phone"]);
expect("usableChannels of nothing is nothing", usableChannels(undefined), []);

// GET /auth/otp/email/delivery/{requestId}: the exact bodies OtpService.emailDelivery answers with.
const status = (state, extra) => ({ requestId: "d6386b03-0000-4000-8000-000000000002", status: state, ...(extra || {}) });
expect("an email still sending is asked about again", deliveryOutcome(status("sending")), { kind: "pending" });
expect("an email that was sent says so", deliveryOutcome(status("sent")), { kind: "sent" });
expect("a late email shows the delayed banner and opens resend",
  deliveryOutcome(status("delayed", { message: "late", resendAfterSeconds: 0 })),
  { kind: "delayed", resendAfterSeconds: 0 });
for (const [failure, reasonKey] of [
  ["otp_delivery_failed", "auth.error.deliveryFailed.email"],
  ["otp_provider_unavailable", "auth.error.providerUnavailable.email"],
  ["otp_service_unavailable", "auth.error.serviceUnavailable.email"],
]) {
  expect(`a failed email (${failure}) says "we couldn't send the code", with its reason, and opens resend`,
    deliveryOutcome(status("failed", { failure, message: "We couldn't send the code. x", resendAfterSeconds: 0 })),
    { kind: "failed", headlineKey: "auth.code.notSent", reasonKey, message: "We couldn't send the code. x", resendAfterSeconds: 0 });
}
expect("a failure this build has no words for still says the code did not go",
  deliveryOutcome(status("failed", { failure: "otp_new_kind", message: "m" })).headlineKey, "auth.code.notSent");
expect("an unreadable status (older server, 404) stops asking", deliveryOutcome(null), { kind: "unknown" });
expect("a status this build does not know stops asking", deliveryOutcome(status("queued")), { kind: "unknown" });

// When the code step stops asking: only "sent" may say the code went.
expect("asking that never settled is shown as a late email with resend open, not as sent",
  deliveryWhenAskingStops(deliveryOutcome(status("sending"))), { kind: "delayed", resendAfterSeconds: 0 });
expect("a status that could not be read is shown as a late email, not as sent",
  deliveryWhenAskingStops(deliveryOutcome(null)), { kind: "delayed", resendAfterSeconds: 0 });
expect("no answer at all is shown as a late email, not as sent",
  deliveryWhenAskingStops(null), { kind: "delayed", resendAfterSeconds: 0 });
expect("a sent email still says sent", deliveryWhenAskingStops(deliveryOutcome(status("sent"))), { kind: "sent" });
expect("a failure is kept as it was said",
  deliveryWhenAskingStops(deliveryOutcome(status("failed", { failure: "otp_delivery_failed", message: "m" }))).kind, "failed");

if (failures > 0) throw new Error(`${failures} check(s) failed`);
log("auth-outcome: all checks pass");
