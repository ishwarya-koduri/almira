package tech.bhrigu.almira.provider

/**
 * The ways a call to an outside service goes wrong that need different answers.
 *
 * Before this, every adapter failure was "an exception", recorded by its class
 * name and shown to nobody. That collapses three situations that could not be
 * more different for the person on the other end:
 *
 *  - the provider did not answer in time — try again, it may already be on its
 *    way;
 *  - the provider answered and said no to *this* message — trying again sends
 *    the same refusal, and the person may need to fix something (a number);
 *  - the provider said no to *us* — the account is out of credit or over its
 *    quota. Nothing the person does will help, and telling them "check your
 *    number" would blame them for our unpaid bill.
 *
 * Four kinds, and no more: each one exists because it changes either whether
 * we retry or what somebody is told. docs/13 "When a provider fails" has the
 * table of what the user and the operator see for each.
 */
enum class FailureKind(
    /** Stable. Stored in outbound_messages.failure and returned in API details. */
    val code: String,
    /** Worth another attempt, within the provider's maxAttempts. */
    val retryable: Boolean,
    /** A problem with our account, not with the person or their message. */
    val accountLevel: Boolean,
) {
    /**
     * No answer within the configured timeout. Transient, so retried — but the
     * request may have landed, so an operation that must not happen twice opts
     * out of retrying it (see [ProviderCalls.execute]).
     */
    TIMEOUT("timeout", retryable = true, accountLevel = false),

    /**
     * The provider could not take the request at all: connection refused, a 503,
     * a maintenance window. Retried, because it usually passes, and safe to retry
     * because nothing was accepted.
     */
    UNAVAILABLE("unavailable", retryable = true, accountLevel = false),

    /**
     * The provider accepted the call and refused this message or request: an
     * invalid or unreachable recipient, a template the operator does not
     * recognise, an expired authorisation code. Permanent for this message, so
     * never retried — the next attempt gets the same answer and, for SMS, may be
     * billed for it.
     */
    REJECTED("rejected", retryable = false, accountLevel = false),

    /**
     * Out of credit, over quota, or suspended. Account-level: never retried,
     * never blamed on the user, and logged at ERROR so whoever runs this server
     * finds out from an alert rather than from a person who could not sign in.
     */
    INSUFFICIENT_BALANCE("insufficient_balance", retryable = false, accountLevel = true),
}

/**
 * Thrown by an adapter to say which [FailureKind] happened.
 *
 * An adapter translates its transport's errors into one of these — a socket
 * timeout into [FailureKind.TIMEOUT], an HTTP 402 or the provider's "no balance"
 * code into [FailureKind.INSUFFICIENT_BALANCE]. Anything else it throws is a bug
 * or a domain refusal, and passes through [ProviderCalls] untouched and
 * unretried.
 *
 * [detail] is for the operator's log. It must never carry a recipient, a
 * one-time code or a message body: exceptions end up in logs, and logs are the
 * least protected thing here (docs/05 §5).
 */
class ProviderFailure(val kind: FailureKind, val detail: String) :
    RuntimeException("${kind.code}: $detail")

/**
 * What [ProviderCalls] throws when it has given up: the kind of the last
 * failure, and how many attempts it made. Callers turn this into what a person
 * sees; they never need to know about backoff.
 */
class ProviderCallFailed(
    val provider: String,
    val operation: String,
    val kind: FailureKind,
    val attempts: Int,
    cause: ProviderFailure,
) : RuntimeException("$provider $operation: ${kind.code} after $attempts attempt(s)", cause)
