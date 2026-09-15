/* =============================================================================
   The Reports completeness number, or none (docs/18 §6, known-issues 19).

   Pure, so scripts/check-completeness.js can assert it without a browser.
   ============================================================================= */

/**
 * The percentage to show, or null when the records have not earned one.
 * A number only when `scoreEarned` is `true`: v1 keeps `score` a required
 * integer, so for a household with nothing recorded the server sends 0 there
 * and says "no number" in `scoreEarned`. Anything else — `false`, or a response
 * without the field — is no number. This client is served by the server it
 * talks to, so there is no older server to be generous to, and a bare 0% (or
 * the 100% servers before 2026-09-14 sent for nothing recorded) is the one
 * thing this card must never show. `scripts/check-spec.py` fails if any client
 * code reads a completeness score without going through this.
 */
export function completenessPercent(report) {
  if (!report || report.scoreEarned !== true || typeof report.score !== "number") return null;
  return `${report.score}%`;
}
