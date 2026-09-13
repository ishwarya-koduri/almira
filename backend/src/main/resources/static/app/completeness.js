/* =============================================================================
   The Reports completeness number, or none (docs/18 §6, known-issues 19).

   Pure, so scripts/check-completeness.js can assert it without a browser.
   ============================================================================= */

/**
 * The percentage to show, or null when the records have not earned one.
 * `scoreEarned: false` means no number, whatever `score` holds: v1 keeps `score`
 * a required integer, so the server sends 0 there and says "no number" in
 * `scoreEarned`. A server from before that field existed sent only `score`,
 * and is shown as it was.
 */
export function completenessPercent(report) {
  if (!report || report.scoreEarned === false || typeof report.score !== "number") return null;
  return `${report.score}%`;
}
