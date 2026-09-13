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

import { signInOutcome, usableChannels } from "../backend/src/main/resources/static/app/auth-outcome.js";

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
  "Your code is taking longer than usual to send. If it arrives, it will work.",
  { requestId: "d6386b03-0000-4000-8000-000000000001", expiresInSeconds: 300, resendAfterSeconds: 30 });

for (const channel of ["phone", "email"]) {
  expect(`${channel}: a delayed send opens the code step with the kept challenge`,
    signInOutcome(delayed, channel),
    { kind: "code", challenge: {
      requestId: "d6386b03-0000-4000-8000-000000000001", expiresInSeconds: 300, resendAfterSeconds: 30,
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

if (failures > 0) throw new Error(`${failures} check(s) failed`);
log("auth-outcome: all checks pass");
