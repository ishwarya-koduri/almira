/* =============================================================================
   What the sign-in screen does with an answer that is not a plain success.

   Kept apart from screens/auth.js, with no imports, so scripts/check-auth-outcome.js
   can assert it without a browser (the provider faults that produce these
   answers have no HTTP surface, by design — see SandboxFaults).

   Three outcomes, one per thing the person should see (docs/api/README.md):

     · code   — 504 otp_delivery_delayed. The server made ONE attempt, kept the
                challenge and lifted the cooldown: the code may still arrive and
                will work, so go to the code step with details.requestId and
                offer a resend after details.resendAfterSeconds — which is 0, so
                resend is open at once (docs/13 "Interactive and background").
                A resend replaces the challenge, and the late code stops
                working. Showing only an error here strands someone whose text
                is thirty seconds late.
     · switch — 403 sign_in_channel_disabled naming details.enabledChannels.
                The server's word beats whatever this screen assumed, including
                the ["phone"] guess made when GET /auth/otp/channels failed.
     · error  — everything else. otp_delivery_failed, otp_provider_unavailable
                and otp_service_unavailable each get their own sentence, because
                "check the number", "try again in a few minutes" and "our
                problem, not yours" are different advice. Nothing was sent, so
                the code step is not opened. Any other code shows the server's
                message as it is.

   And, for email sign-in, what the code step does with the delivery status
   (GET /auth/otp/email/delivery/{requestId}, deliveryOutcome below). The
   request answers before the email is sent — so that an address off the alpha
   allowlist cannot be told from one on it — and the code step polls how the
   send went, because a failure nobody is told about looks exactly like a code
   that never arrived:

     · pending — "sending": ask again shortly.
     · sent    — say the code was sent.
     · delayed — the delayed banner, resend open now (resendAfterSeconds 0).
     · failed  — "We couldn't send the code." with the reason's own sentence,
                 resend open now. The code step stays: the address is right
                 there to change, and resend is the retry.
     · unknown — anything else (a server without the endpoint, 404, a shape
                 this build does not know): stop asking. Like 30 polls that
                 never settle, it is unconfirmed, and deliveryWhenAskingStops
                 shows it as the delayed banner with resend open, never as sent.
   ============================================================================= */

export const KNOWN_CHANNELS = ["phone", "email"];

/** Only the channels this client can draw, in the order the server gave them. */
export function usableChannels(list) {
  if (!Array.isArray(list)) return [];
  return list.filter((c, i) => KNOWN_CHANNELS.includes(c) && list.indexOf(c) === i);
}

const SEPARATE_MESSAGES = {
  otp_delivery_failed: { key: "auth.error.deliveryFailed", onField: true },
  otp_provider_unavailable: { key: "auth.error.providerUnavailable", onField: false },
  otp_service_unavailable: { key: "auth.error.serviceUnavailable", onField: false },
};

function isCount(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

/**
 * @param error    what the api client threw (an ApiError: status, code, message, details)
 * @param channel  the channel that was being used ("phone" | "email")
 * @returns {{kind:"code", challenge}} | {{kind:"switch", channels, channel}} | {{kind:"error", messageKey, onField, message}}
 */
export function signInOutcome(error, channel) {
  const code = error && error.code;
  const details = (error && error.details) || {};

  if (error && error.status === 504 && code === "otp_delivery_delayed") {
    const { requestId, expiresInSeconds, resendAfterSeconds } = details;
    if (typeof requestId === "string" && requestId && isCount(expiresInSeconds) && isCount(resendAfterSeconds)) {
      return {
        kind: "code",
        challenge: { requestId, expiresInSeconds, resendAfterSeconds, channel, delayed: true },
      };
    }
    // A 504 without a challenge in it gives the code step nothing to verify
    // against: say what the server said and stay put.
  }

  if (error && error.status === 403 && code === "sign_in_channel_disabled") {
    const named = usableChannels(details.enabledChannels);
    if (named.length && !named.includes(channel)) {
      return { kind: "switch", channels: named, channel: named[0] };
    }
  }

  const separate = SEPARATE_MESSAGES[code];
  if (separate) {
    return { kind: "error", messageKey: `${separate.key}.${channel}`, onField: separate.onField, message: error.message };
  }
  return { kind: "error", messageKey: null, onField: true, message: (error && error.message) || "" };
}

const FAILURE_REASONS = {
  otp_delivery_failed: "auth.error.deliveryFailed.email",
  otp_provider_unavailable: "auth.error.providerUnavailable.email",
  otp_service_unavailable: "auth.error.serviceUnavailable.email",
};

/**
 * @param status  the parsed body of GET /auth/otp/email/delivery/{requestId}, or null when it could not be read
 * @returns {{kind:"pending"}} | {{kind:"sent"}} | {{kind:"delayed", resendAfterSeconds:0}}
 *        | {{kind:"failed", headlineKey, reasonKey, message, resendAfterSeconds:0}} | {{kind:"unknown"}}
 */
export function deliveryOutcome(status) {
  const state = status && status.status;
  if (state === "sending") return { kind: "pending" };
  if (state === "sent") return { kind: "sent" };
  if (state === "delayed") return { kind: "delayed", resendAfterSeconds: 0 };
  if (state === "failed") {
    return {
      kind: "failed",
      headlineKey: "auth.code.notSent",
      // A reason this build has no words for still says the code did not go.
      reasonKey: FAILURE_REASONS[status.failure] || null,
      message: typeof status.message === "string" ? status.message : "",
      resendAfterSeconds: 0,
    };
  }
  return { kind: "unknown" };
}

/**
 * What the code step shows once it stops asking about the email.
 *
 * Only a status that said "sent" may say the code was sent. Asking that never
 * settled (30 polls still "sending": the server's settle never ran) or a status
 * that could not be read is unconfirmed, and unconfirmed is shown as a late
 * email with resend open, never as a code that went: that silence is the one
 * the code step exists to end.
 *
 * @param last  the last deliveryOutcome seen, or null if none was
 */
export function deliveryWhenAskingStops(last) {
  if (last && (last.kind === "sent" || last.kind === "delayed" || last.kind === "failed")) return last;
  return { kind: "delayed", resendAfterSeconds: 0 };
}
