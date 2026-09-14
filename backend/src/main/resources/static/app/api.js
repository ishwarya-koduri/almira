/* =============================================================================
   API client.

   Token handling, stated plainly: the access token lives in memory only, and
   the refresh token in localStorage so a page reload does not sign you out.
   localStorage is readable by any script on the origin, so this trades some XSS
   resistance for not making people re-authenticate constantly. That trade is
   acceptable for a companion web client and is NOT acceptable for the mobile
   app, which keeps tokens in the Keychain/Keystore behind a biometric lock
   (docs/05 §2). Worth revisiting here with httpOnly cookies if this UI ever
   becomes the primary surface.
   ============================================================================= */

import { remember, forget, reset, peek } from "./cache.js";
import { clearAllDrafts } from "./drafts.js";

const REFRESH_KEY = "almira.refresh";

let accessToken = null;
let refreshing = null;

export const auth = {
  get refreshToken() {
    try { return localStorage.getItem(REFRESH_KEY); } catch { return null; }
  },
  set(tokens) {
    accessToken = tokens.accessToken;
    try { localStorage.setItem(REFRESH_KEY, tokens.refreshToken); } catch { /* private mode */ }
  },
  clear() {
    accessToken = null;
    // Signed out, or the session ended: the last known views go with it (cache.js).
    reset();
    try { localStorage.removeItem(REFRESH_KEY); } catch { /* ignore */ }
    // Drafts and saves waiting for a network belong to the person who typed
    // them; the next person to sign in on this phone must not find them (X-83).
    try { clearAllDrafts(localStorage); } catch { /* ignore */ }
  },
  get isSignedIn() { return Boolean(accessToken || auth.refreshToken); },
};

export class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details || {};
  }
  /** True when a field-level message should be shown inline on the form. */
  get fieldErrors() { return this.details.fields || null; }
}

async function raw(method, path, body, token) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  return { response, payload };
}

/**
 * One refresh at a time. Without this guard, a screen that fires several
 * requests at once would send several refreshes with the same token — and the
 * second one looks exactly like token reuse to the server, which responds by
 * revoking the whole session. Concurrency here would log people out.
 */
async function refreshTokens() {
  if (refreshing) return refreshing;
  const token = auth.refreshToken;
  if (!token) return null;

  refreshing = (async () => {
    const { response, payload } = await raw("POST", "/api/v1/auth/refresh", { refreshToken: token });
    if (!response.ok) { auth.clear(); return null; }
    auth.set(payload);
    return payload.accessToken;
  })().finally(() => { refreshing = null; });

  return refreshing;
}

async function request(method, path, body, { retry = true } = {}) {
  if (!accessToken && auth.refreshToken) await refreshTokens();

  let { response, payload } = await raw(method, path, body, accessToken);

  if (response.status === 401 && retry && auth.refreshToken) {
    const fresh = await refreshTokens();
    if (fresh) ({ response, payload } = await raw(method, path, body, fresh));
  }

  if (!response.ok) {
    const error = payload?.error || {};
    throw new ApiError(
      response.status,
      error.code || "unknown",
      error.message || "Something went wrong.",
      error.details,
    );
  }
  // A read is kept as the last known view; any write makes every kept view
  // possibly stale, so all of them go (cache.js).
  if (method === "GET") remember(path, payload);
  else forget();
  return payload;
}

/** Sign-in calls: no token, no refresh, and the server's error as an ApiError. */
async function unauthenticated(method, path, body) {
  const { response, payload } = await raw(method, path, body);
  if (!response.ok) {
    const e = payload?.error || {};
    throw new ApiError(response.status, e.code || "unknown", e.message || "Something went wrong.", e.details);
  }
  return payload;
}

export const api = {
  get:    (path)       => request("GET", path),
  post:   (path, body) => request("POST", path, body),
  patch:  (path, body) => request("PATCH", path, body),
  put:    (path, body) => request("PUT", path, body),
  del:    (path)       => request("DELETE", path),

  /** The last known answer for a read, drawn at once while a fresh one is fetched (X-38). */
  peek,

  // --- auth -----------------------------------------------------------------
  /**
   * Which ways in this server offers: ["phone"], ["email"] or both. A server
   * from before email existed has no such endpoint, and that means phone.
   * That is a guess whenever this call fails for any other reason, so it is not
   * the last word: a 403 sign_in_channel_disabled names the channels that are
   * on, and the sign-in screen switches to those (auth-outcome.js).
   */
  signInChannels: async () => {
    try {
      const { response, payload } = await raw("GET", "/api/v1/auth/otp/channels");
      const channels = response.ok ? (payload?.channels || []).filter((c) => c === "phone" || c === "email") : [];
      return channels.length ? channels : ["phone"];
    } catch {
      return ["phone"];
    }
  },

  requestOtp: (phone) => unauthenticated("POST", "/api/v1/auth/otp/request", { phone }),

  verifyOtp: async (phone, code, requestId) => {
    const payload = await unauthenticated("POST", "/api/v1/auth/otp/verify", {
      phone, code, requestId, deviceName: deviceName(),
    });
    auth.set(payload);
    return payload;
  },

  /**
   * Sign-in by email. The request answers the same whether or not the address
   * may sign in, so there is nothing here to branch on: go to the code step,
   * which asks emailDelivery how the send went.
   */
  requestEmailOtp: (email) => unauthenticated("POST", "/api/v1/auth/otp/email/request", { email }),

  /**
   * How the email for a sign-in request went: sending, sent, delayed or failed.
   * Null when it cannot be read (an older server has no such endpoint).
   */
  emailDelivery: async (requestId) => {
    try {
      const { response, payload } = await raw("GET", `/api/v1/auth/otp/email/delivery/${encodeURIComponent(requestId)}`);
      return response.ok ? payload : null;
    } catch {
      return null;
    }
  },

  verifyEmailOtp: async (email, code, requestId) => {
    const payload = await unauthenticated("POST", "/api/v1/auth/otp/email/verify", {
      email, code, requestId, deviceName: deviceName(),
    });
    auth.set(payload);
    return payload;
  },

  /**
   * The second step of a sign-in, for an account with a second factor. The
   * first step answered 401 second_factor_required with details.secondFactorToken
   * and details.methods; each of these finishes it and signs in.
   */
  completeWithAuthenticator: async (secondFactorToken, code) => {
    const payload = await unauthenticated("POST", "/api/v1/auth/second-factor/authenticator", { secondFactorToken, code });
    auth.set(payload);
    return payload;
  },
  completeWithRecoveryCode: async (secondFactorToken, code) => {
    const payload = await unauthenticated("POST", "/api/v1/auth/second-factor/recovery-code", { secondFactorToken, code });
    auth.set(payload);
    return payload;
  },
  secondFactorPasskeyOptions: (secondFactorToken) =>
    unauthenticated("POST", "/api/v1/auth/second-factor/passkey/options", { secondFactorToken }),
  completeWithPasskey: async (secondFactorToken, requestId, credential) => {
    const payload = await unauthenticated("POST", "/api/v1/auth/second-factor/passkey", { secondFactorToken, requestId, credential });
    auth.set(payload);
    return payload;
  },

  signOut: async () => {
    try { await request("POST", "/api/v1/auth/logout"); } catch { /* leaving anyway */ }
    auth.clear();
  },

  // --- resources ------------------------------------------------------------
  me:            ()               => api.get("/api/v1/me"),
  households:    ()               => api.get("/api/v1/households"),
  createHousehold: (body)         => api.post("/api/v1/households", body),
  members:       (hid)            => api.get(`/api/v1/households/${hid}/members`),
  addMember:     (hid, body)      => api.post(`/api/v1/households/${hid}/members`, body),
  taxonomy:      (hid)            => api.get(`/api/v1/households/${hid}/taxonomy`),
  institutions:  (hid, q)         => api.get(`/api/v1/households/${hid}/institutions${q ? `?q=${encodeURIComponent(q)}` : ""}`),
  investments:   (hid, query)     => api.get(`/api/v1/households/${hid}/investments${query ? `?${query}` : ""}`),
  investment:    (hid, id)        => api.get(`/api/v1/households/${hid}/investments/${id}`),
  capture:       (hid, body)      => api.post(`/api/v1/households/${hid}/investments`, body),
  updateInvestment: (hid, id, b)  => api.patch(`/api/v1/households/${hid}/investments/${id}`, b),
  setVisibility: (hid, id, b)     => api.patch(`/api/v1/households/${hid}/investments/${id}/visibility`, b),
  addValuation:  (hid, id, b)     => api.post(`/api/v1/households/${hid}/investments/${id}/valuations`, b),
  valuations:    (hid, id)        => api.get(`/api/v1/households/${hid}/investments/${id}/valuations`),
  archive:       (hid, id)        => api.del(`/api/v1/households/${hid}/investments/${id}`),
  restore:       (hid, id)        => api.post(`/api/v1/households/${hid}/trash/investments/${id}/restore`),
  trash:         (hid)            => api.get(`/api/v1/households/${hid}/trash`),
  dashboardPath: (hid, scope, m)  =>
    `/api/v1/households/${hid}/dashboard?scope=${scope}${m ? `&member=${m}` : ""}`,
  dashboard:     (hid, scope, m)  => api.get(api.dashboardPath(hid, scope, m)),
  trendPath:     (hid, scope, m)  =>
    `/api/v1/households/${hid}/reports/net-worth-trend?months=12&scope=${scope}${m ? `&member=${m}` : ""}`,
  netWorthTrend: (hid, scope, m)  => api.get(api.trendPath(hid, scope, m)),
  // "To review" (X-51): everything waiting for this person, in order.
  reviewPath:    (hid)            => `/api/v1/households/${hid}/review`,
  review:        (hid)            => api.get(api.reviewPath(hid)),
  // "What Ravi sees" (X-56): built by the server from what you can see.
  memberPreview: (hid, memberId)  => api.get(`/api/v1/households/${hid}/members/${memberId}/preview`),
  reminders:     (hid)            => api.get(`/api/v1/households/${hid}/reminders`),
  documentsFor:  (hid, type, id)  => api.get(
    `/api/v1/households/${hid}/documents?entityType=${encodeURIComponent(type)}&entityId=${encodeURIComponent(id)}`),
  invite:        (hid, body)      => api.post(`/api/v1/households/${hid}/invitations`, body),

  // --- accounts -------------------------------------------------------------
  accounts:      (hid)            => api.get(`/api/v1/households/${hid}/accounts`),
  account:       (hid, id)        => api.get(`/api/v1/households/${hid}/accounts/${id}`),
  createAccount: (hid, body)      => api.post(`/api/v1/households/${hid}/accounts`, body),
  updateAccount: (hid, id, body)  => api.patch(`/api/v1/households/${hid}/accounts/${id}`, body),
  deleteAccount: (hid, id)        => api.del(`/api/v1/households/${hid}/accounts/${id}`),
  revealNumber:  (hid, id)        => api.post(`/api/v1/households/${hid}/accounts/${id}/reveal-number`),

  // --- step-up --------------------------------------------------------------
  stepUpStatus:  ()               => api.get("/api/v1/auth/step-up"),
  stepUpRequest: ()               => api.post("/api/v1/auth/step-up/request"),
  stepUpVerify:  (body)           => api.post("/api/v1/auth/step-up/verify", body),
  stepUpAuthenticator: (code)     => api.post("/api/v1/auth/step-up/authenticator", { code }),
  stepUpRecoveryCode: (code)      => api.post("/api/v1/auth/step-up/recovery-code", { code }),
  stepUpPasskeyOptions: ()        => api.post("/api/v1/auth/step-up/passkey/options"),
  stepUpPasskey: (requestId, credential) => api.post("/api/v1/auth/step-up/passkey", { requestId, credential }),

  // --- how you sign in ----------------------------------------------------------
  signInMethods: ()               => api.get("/api/v1/auth/sign-in-methods"),
  beginAuthenticator: ()          => api.post("/api/v1/auth/authenticator"),
  confirmAuthenticator: (code)    => api.post("/api/v1/auth/authenticator/confirm", { code }),
  removeAuthenticator: ()         => api.del("/api/v1/auth/authenticator"),
  replaceRecoveryCodes: ()        => api.post("/api/v1/auth/recovery-codes"),
  passkeyOptions: ()              => api.post("/api/v1/auth/passkeys/options"),
  addPasskey:    (body)           => api.post("/api/v1/auth/passkeys", body),
  removePasskey: (id)             => api.del(`/api/v1/auth/passkeys/${encodeURIComponent(id)}`),
  requestPhoneChange: (phone)     => api.post("/api/v1/auth/phone/request", { phone }),
  verifyPhoneChange: (body)       => api.post("/api/v1/auth/phone/verify", body),

  // --- liabilities ----------------------------------------------------------
  liabilities:   (hid)            => api.get(`/api/v1/households/${hid}/liabilities`),
  liability:     (hid, id)        => api.get(`/api/v1/households/${hid}/liabilities/${id}`),
  createLiability: (hid, body)    => api.post(`/api/v1/households/${hid}/liabilities`, body),
  updateLiability: (hid, id, b)   => api.patch(`/api/v1/households/${hid}/liabilities/${id}`, b),
  recordBalance: (hid, id, body)  => api.post(`/api/v1/households/${hid}/liabilities/${id}/balances`, body),
  setLiabilityVisibility: (hid, id, b) =>
    api.patch(`/api/v1/households/${hid}/liabilities/${id}/visibility`, b),
  linkSecuredAsset: (hid, id, b)  => api.post(`/api/v1/households/${hid}/liabilities/${id}/secured-by`, b),
  deleteLiability: (hid, id)      => api.del(`/api/v1/households/${hid}/liabilities/${id}`),

  // --- still true? (docs/21) --------------------------------------------------
  stillTrue:     (hid)            => api.get(`/api/v1/households/${hid}/still-true`),
  confirmStillTrue: (hid, type, id) =>
    api.post(`/api/v1/households/${hid}/still-true/${type}/${id}/confirm`),
  snoozeStillTrue: (hid, type, id, until) =>
    api.post(`/api/v1/households/${hid}/still-true/${type}/${id}/snooze`, { until }),
  askLaterAboutStillTrue: () => api.post("/api/v1/me/notification-preferences/still-true/ask-later"),

  // --- notifications (docs/13 "Pacing") -----------------------------------------
  notificationPreferences: () => api.get("/api/v1/me/notification-preferences"),
  updateNotificationPreferences: (body) => api.put("/api/v1/me/notification-preferences", body),

  // --- nominees -------------------------------------------------------------
  // PUT, not PATCH: the nominee list is replaced as a unit.
  setNominees:   (hid, id, body)  =>
    api.put(`/api/v1/households/${hid}/investments/${id}/nominees`, body),
  acceptInvite:  (token)          => api.post("/api/v1/invitations/accept", { token }),

  // --- the first session (docs/03 §1) ---------------------------------------
  readinessCheck: ()              => api.get("/api/v1/me/readiness-check"),
  answerReadinessCheck: (answers) => api.put("/api/v1/me/readiness-check", { answers }),
  firstSession:  (hid)            => api.get(`/api/v1/households/${hid}/first-session`),
  updateFirstSession: (hid, body) => api.put(`/api/v1/households/${hid}/first-session`, body),
  welcome:       (hid)            => api.get(`/api/v1/households/${hid}/welcome`),
  welcomeSeen:   (hid)            => api.post(`/api/v1/households/${hid}/welcome/seen`),
  checklists:    (hid)            => api.get(`/api/v1/households/${hid}/guidance/checklists`),
  supportContact: ()              => api.get("/api/v1/support/contact"),

  // --- goals ----------------------------------------------------------------
  goals:         (hid)            => api.get(`/api/v1/households/${hid}/goals`),
  goal:          (hid, id)        => api.get(`/api/v1/households/${hid}/goals/${id}`),
  createGoal:    (hid, body)      => api.post(`/api/v1/households/${hid}/goals`, body),
  updateGoal:    (hid, id, body)  => api.patch(`/api/v1/households/${hid}/goals/${id}`, body),
  mapToGoal:     (hid, id, body)  => api.post(`/api/v1/households/${hid}/goals/${id}/investments`, body),
  unmapFromGoal: (hid, id, iid)   => api.del(`/api/v1/households/${hid}/goals/${id}/investments/${iid}`),
  archiveGoal:   (hid, id)        => api.del(`/api/v1/households/${hid}/goals/${id}`),
  unallocated:   (hid)            => api.get(`/api/v1/households/${hid}/goals-unallocated`),

  // --- returns --------------------------------------------------------------
  returns:       (hid, groupBy)   => api.get(`/api/v1/households/${hid}/returns?groupBy=${groupBy || "total"}`),
  investmentReturns: (hid, id)    => api.get(`/api/v1/households/${hid}/investments/${id}/returns`),
  transactions:  (hid, id)        => api.get(`/api/v1/households/${hid}/investments/${id}/transactions`),
  recordTransaction: (hid, id, b) => api.post(`/api/v1/households/${hid}/investments/${id}/transactions`, b),
  taxLots:       (hid, id)        => api.get(`/api/v1/households/${hid}/investments/${id}/tax-lots`),

  // --- tax ------------------------------------------------------------------
  taxPack:       (hid, fy, member) => api.get(
    `/api/v1/households/${hid}/tax/pack?${new URLSearchParams({
      ...(fy ? { fy } : {}), ...(member ? { member } : {}),
    })}`),

  // The files a CA needs (docs/tax/capital-gains.md), fetched with the bearer
  // token by downloadAuthenticated. `query` is a URLSearchParams of fy and member.
  taxPackPdfUrl: (hid, query)     => `/api/v1/households/${hid}/tax/pack/pdf?${query}`,
  schedule112aUrl: (hid, query)   => `/api/v1/households/${hid}/tax/schedule-112a?${query}`,
  setFmv2018:    (hid, id, body)  => api.put(`/api/v1/households/${hid}/tax/grandfathering/${id}`, body),

  // --- templates ------------------------------------------------------------
  templates:     (hid)            => api.get(`/api/v1/households/${hid}/templates`),
  createTemplate: (hid, body)     => api.post(`/api/v1/households/${hid}/templates`, body),
  applyTemplate: (hid, id, body)  => api.post(`/api/v1/households/${hid}/templates/${id}/apply`, body),
  deleteTemplate: (hid, id)       => api.del(`/api/v1/households/${hid}/templates/${id}`),

  // --- duplicate and renew --------------------------------------------------
  duplicate:     (hid, id, body)  => api.post(`/api/v1/households/${hid}/investments/${id}/duplicate`, body || {}),
  rollover:      (hid, id, body)  => api.post(`/api/v1/households/${hid}/investments/${id}/rollover`, body || {}),

  // --- reports --------------------------------------------------------------
  completeness:  (hid)            => api.get(`/api/v1/households/${hid}/reports/completeness`),
  insights:      (hid)            => api.get(`/api/v1/households/${hid}/reports/insights`),
  exportUrl:     (hid, format)    => `/api/v1/households/${hid}/reports/export?format=${format}`,

  // --- capture and import ---------------------------------------------------
  parseText:     (hid, text)      => api.post(`/api/v1/households/${hid}/capture/parse-text`, { text }),
  parseDocument: (hid, file)      => upload(`/api/v1/households/${hid}/capture/parse-document`, file),
  importPreview: (hid, file)      => upload(`/api/v1/households/${hid}/import/preview`, file),
  runImport:     (hid, file, options) =>
    upload(`/api/v1/households/${hid}/import`, file, options),

  // --- currencies and providers ---------------------------------------------
  rates:         (hid, quote)     => api.get(`/api/v1/households/${hid}/rates?quote=${quote || "INR"}`),
  recordRate:    (hid, body)      => api.post(`/api/v1/households/${hid}/rates`, body),
  convert:       (hid, amount, from, to) =>
    api.get(`/api/v1/households/${hid}/rates/convert?amount=${encodeURIComponent(amount)}` +
      `&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to || "INR")}`),
  providers:     (hid)            => api.get(`/api/v1/households/${hid}/connect/providers`),

  // --- zero-knowledge mode --------------------------------------------------
  e2eStatus:     (hid)            => api.get(`/api/v1/households/${hid}/e2e`),
  putE2eKey:     (hid, body)      => api.put(`/api/v1/households/${hid}/e2e/key`, body),
  sealedValues:  (hid, type, id)  => api.get(
    `/api/v1/households/${hid}/e2e/values${type ? `?recordType=${type}&recordId=${id}` : ""}`),
  sealValue:     (hid, type, id, field, body) =>
    api.put(`/api/v1/households/${hid}/e2e/values/${type}/${id}/${field}`, body),
  unsealValue:   (hid, type, id, field) =>
    api.del(`/api/v1/households/${hid}/e2e/values/${type}/${id}/${field}`),
  // Recovery copies of the content key (docs/12 §10). Codes and shares never go here.
  recovery:      (hid)            => api.get(`/api/v1/households/${hid}/e2e/recovery`),
  recoveryFor:   (hid, memberId)  => api.get(`/api/v1/households/${hid}/e2e/recovery/members/${memberId}`),
  putRecovery:   (hid, kind, body) => api.put(`/api/v1/households/${hid}/e2e/recovery/${kind}`, body),
  removeRecovery: (hid, kind)     => api.del(`/api/v1/households/${hid}/e2e/recovery/${kind}`),
  practiseRecovery: (hid, kind)   => api.post(`/api/v1/households/${hid}/e2e/recovery/${kind}/practice`),
  // Every record with a physical original, with its two sealed slots (docs/20).
  whereAndWho:   (hid, type, id)  => api.get(
    `/api/v1/households/${hid}/where-and-who${type ? `?recordType=${type}&recordId=${id}` : ""}`),

  // --- estate, contacts and continuity --------------------------------------
  contacts:      (hid, query)     => api.get(`/api/v1/households/${hid}/contacts${query ? `?${query}` : ""}`),
  createContact: (hid, body)      => api.post(`/api/v1/households/${hid}/contacts`, body),
  linkContact:   (hid, id, body)  => api.post(`/api/v1/households/${hid}/contacts/${id}/links`, body),
  deleteContact: (hid, id)        => api.del(`/api/v1/households/${hid}/contacts/${id}`),
  estateDocuments: (hid)          => api.get(`/api/v1/households/${hid}/estate/documents`),
  createEstateDocument: (hid, b)  => api.post(`/api/v1/households/${hid}/estate/documents`, b),
  updateEstateDocument: (hid, id, b) => api.patch(`/api/v1/households/${hid}/estate/documents/${id}`, b),
  mismatches:    (hid)            => api.get(`/api/v1/households/${hid}/estate/mismatches`),
  transmission:  (hid, id)        => api.get(`/api/v1/households/${hid}/continuity/transmission/${id}`),
  handbook:      (hid)            => api.get(`/api/v1/households/${hid}/continuity/handbook`),
  readiness:     (hid)            => api.get(`/api/v1/households/${hid}/continuity/readiness`),
  handbookPdfUrl: (hid)           => `/api/v1/households/${hid}/continuity/handbook.pdf`,

  // --- sharing and emergency access -----------------------------------------
  shares:        (hid)            => api.get(`/api/v1/households/${hid}/shares`),
  createShare:   (hid, body)      => api.post(`/api/v1/households/${hid}/shares`, body),
  shareViews:    (hid, id)        => api.get(`/api/v1/households/${hid}/shares/${id}/views`),
  revokeShare:   (hid, id)        => api.del(`/api/v1/households/${hid}/shares/${id}`),
  trustedContacts: (hid)          => api.get(`/api/v1/households/${hid}/emergency/contacts`),
  nameTrustedContact: (hid, body) => api.post(`/api/v1/households/${hid}/emergency/contacts`, body),
  removeTrustedContact: (hid, id) => api.del(`/api/v1/households/${hid}/emergency/contacts/${id}`),
  emergencyRequests: (hid)        => api.get(`/api/v1/households/${hid}/emergency/requests`),
  requestEmergencyAccess: (hid, b) => api.post(`/api/v1/households/${hid}/emergency/requests`, b),
  vetoEmergencyAccess: (hid, id)  => api.post(`/api/v1/households/${hid}/emergency/requests/${id}/veto`),
  withdrawEmergencyAccess: (hid, id) =>
    api.post(`/api/v1/households/${hid}/emergency/requests/${id}/withdraw`),
  // What naming someone would mean, dated, before it is done (X-41).
  emergencyPreview: (hid, trustedMemberId, waitDays) => api.get(
    `/api/v1/households/${hid}/emergency/preview?trustedMemberId=${encodeURIComponent(trustedMemberId)}&waitDays=${Number(waitDays)}`),

  // --- heir mode (X-40) -------------------------------------------------------
  heirPlan:      (hid, rid)       => api.get(`/api/v1/households/${hid}/emergency/requests/${rid}/heir`),
  startHeirPlan: (hid, rid, situation) =>
    api.post(`/api/v1/households/${hid}/emergency/requests/${rid}/heir`, { situation }),
  pauseHeirPlan: (hid, rid)       => api.post(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/pause`),
  resumeHeirPlan: (hid, rid)      => api.post(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/resume`),
  setHeirTask:   (hid, rid, taskId, status) =>
    api.patch(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/tasks/${taskId}`, { status }),
  assignHeirTask: (hid, rid, taskId, helperId) =>
    api.put(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/tasks/${taskId}/helper`, { helperId }),
  addHeirHelper: (hid, rid, body) => api.post(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/helpers`, body),
  removeHeirHelper: (hid, rid, helperId) =>
    api.del(`/api/v1/households/${hid}/emergency/requests/${rid}/heir/helpers/${helperId}`),

  // --- printed pages (X-61, P-28) ---------------------------------------------
  emergencyKitPdfUrl: (hid)       => `/api/v1/households/${hid}/continuity/emergency-kit.pdf`,
  // A POST: it makes the edition's link and withdraws the last one's.
  envelopePdfUrl: (hid)           => `/api/v1/households/${hid}/continuity/handbook/envelope.pdf`,

  // --- guided flows (X-58): the step, saved at every step ---------------------
  guidedDraft:   (hid, flow, subject = "") =>
    api.get(`/api/v1/households/${hid}/guided-flows/${flow}?subject=${encodeURIComponent(subject)}`),
  saveGuidedDraft: (hid, flow, subject = "", body) =>
    api.put(`/api/v1/households/${hid}/guided-flows/${flow}?subject=${encodeURIComponent(subject)}`, body),
  discardGuidedDraft: (hid, flow, subject = "") =>
    api.del(`/api/v1/households/${hid}/guided-flows/${flow}?subject=${encodeURIComponent(subject)}`),

  // --- the lost-money sweep (P-25) --------------------------------------------
  lostMoney:     (hid)            => api.get(`/api/v1/households/${hid}/lost-money`),
  recordLostMoneyCheck: (hid, portal, body) => api.put(`/api/v1/households/${hid}/lost-money/${portal}`, body),
  recordFoundMoney: (hid, portal, body) => api.post(`/api/v1/households/${hid}/lost-money/${portal}/found`, body),

  // --- continuity signals (docs/27) -----------------------------------------
  inactivity:    (hid)            => api.get(`/api/v1/households/${hid}/emergency/inactivity`),
  setInactivity: (hid, body)      => api.put(`/api/v1/households/${hid}/emergency/inactivity`, body),
  imHere:        (hid)            => api.post(`/api/v1/households/${hid}/emergency/inactivity/check-in`),
  confirmReachable: (hid, contactId) =>
    api.post(`/api/v1/households/${hid}/emergency/contacts/${contactId}/reachable`),
  askReachable:  (hid, contactId) =>
    api.post(`/api/v1/households/${hid}/emergency/contacts/${contactId}/reachable/ask`),
  keyHolderAsks: (hid)            => api.get(`/api/v1/households/${hid}/key-holder-asks`),
  askKeyHolder:  (hid, body)      => api.post(`/api/v1/households/${hid}/key-holder-asks`, body),
  answerKeyHolder: (hid, id, answer) => api.post(`/api/v1/households/${hid}/key-holder-asks/${id}/answer`, { answer }),
  withdrawKeyHolderAsk: (hid, id) => api.del(`/api/v1/households/${hid}/key-holder-asks/${id}`),
  confirmChain:  (hid, type, id, position) =>
    api.put(`/api/v1/households/${hid}/where-and-who/${type}/${id}/chain/${position}/confirmation`),
  unconfirmChain: (hid, type, id, position) =>
    api.del(`/api/v1/households/${hid}/where-and-who/${type}/${id}/chain/${position}/confirmation`),
  protection:    (hid)            => api.get(`/api/v1/households/${hid}/continuity/protection`),
  setProtectionInputs: (hid, body) => api.put(`/api/v1/households/${hid}/continuity/protection/inputs`, body),
  /** A one-tap link. Nobody is signed in: the token in the body is the authority. */
  redeemContinuityLink: (token)   => unauthenticated("POST", "/api/v1/continuity-links/redeem", { token }),

  // --- documents ------------------------------------------------------------
  documents:     (hid)            => api.get(`/api/v1/households/${hid}/documents`),
  documentAccess: (hid, id)       => api.post(`/api/v1/households/${hid}/documents/${id}/access`),
  // A scan attached to a record, from its Papers section (X-53).
  attachDocument: (hid, type, id, file) =>
    upload(`/api/v1/households/${hid}/documents`, file, { entityType: type, entityId: id }),
  documentDownloadUrl: (token)    => `/api/v1/documents/download?token=${encodeURIComponent(token)}`,

  // --- data rights (docs/23 "Your data rights") -------------------------------
  privacy:       ()               => api.get("/api/v1/me/privacy"),
  acceptNotice:  (version)        => api.post("/api/v1/me/privacy/notice/accept", { version }),
  changeConsent: (purpose, given) => api.post("/api/v1/me/privacy/consents", { purpose, given }),
  consentHistory: ()              => api.get("/api/v1/me/privacy/history"),
  accessSummary: ()               => api.get("/api/v1/me/privacy/summary"),
  rightsRequest: (body)           => api.post("/api/v1/me/privacy/requests", body),
  withdrawRightsRequest: (id)     => api.post(`/api/v1/me/privacy/requests/${id}/withdraw`),
  nominate:      (body)           => api.post("/api/v1/me/privacy/nominees", body),
  revokeNominee: (id)             => api.del(`/api/v1/me/privacy/nominees/${id}`),
  parentalConsents: (hid)         => api.get(`/api/v1/households/${hid}/parental-consents`),
  giveParentalConsent: (hid, memberId, body) =>
    api.post(`/api/v1/households/${hid}/members/${memberId}/parental-consent`, body),
  withdrawParentalConsent: (hid, id) =>
    api.post(`/api/v1/households/${hid}/parental-consents/${id}/withdraw`),
};

/**
 * Multipart, not JSON — and deliberately not going through `request`, which
 * sets a JSON content type. The browser must set its own multipart boundary,
 * so the Content-Type header is left alone here.
 */
async function upload(path, file, fields = {}) {
  const send = async (token) => {
    const form = new FormData();
    form.append("file", file);
    for (const [key, value] of Object.entries(fields)) {
      if (value !== null && value !== undefined) {
        form.append(key, typeof value === "object" ? JSON.stringify(value) : String(value));
      }
    }
    const response = await fetch(path, {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    });
    const text = await response.text();
    return { response, payload: text ? JSON.parse(text) : null };
  };

  if (!accessToken && auth.refreshToken) await refreshTokens();
  let { response, payload } = await send(accessToken);
  if (response.status === 401 && auth.refreshToken) {
    const fresh = await refreshTokens();
    if (fresh) ({ response, payload } = await send(fresh));
  }
  if (!response.ok) {
    const error = payload?.error || {};
    throw new ApiError(response.status, error.code || "unknown",
      error.message || "Something went wrong.", error.details);
  }
  forget();
  return payload;
}

/**
 * A download that carries the Authorization header — an ordinary link cannot,
 * and the export endpoint is authenticated like everything else.
 */
export async function downloadAuthenticated(path, fallbackName, { method = "GET" } = {}) {
  if (!accessToken && auth.refreshToken) await refreshTokens();
  const response = await fetch(path, { method, headers: { Authorization: `Bearer ${accessToken}` } });
  if (!response.ok) {
    // The server's own reason when it gave one — "confirm it's you", "nothing
    // to print yet" — rather than a generic failure.
    const error = await response.json().then((body) => body?.error).catch(() => null);
    throw new ApiError(response.status, error?.code || "download_failed",
      error?.message || "That download didn't work.", error?.details);
  }
  const disposition = response.headers.get("Content-Disposition") || "";
  const named = disposition.match(/filename="?([^"]+)"?/);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = named ? named[1] : fallbackName;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function deviceName() {
  const ua = navigator.userAgent;
  const browser = /Firefox/.test(ua) ? "Firefox"
    : /Edg/.test(ua) ? "Edge"
    : /Chrome/.test(ua) ? "Chrome"
    : /Safari/.test(ua) ? "Safari" : "Browser";
  const os = /Mac/.test(ua) ? "Mac" : /Win/.test(ua) ? "Windows"
    : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : "";
  return [browser, os].filter(Boolean).join(" on ");
}
