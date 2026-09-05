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
    try { localStorage.removeItem(REFRESH_KEY); } catch { /* ignore */ }
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
    const { response, payload } = await raw("POST", "/api/auth/refresh", { refreshToken: token });
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
  return payload;
}

export const api = {
  get:    (path)       => request("GET", path),
  post:   (path, body) => request("POST", path, body),
  patch:  (path, body) => request("PATCH", path, body),
  put:    (path, body) => request("PUT", path, body),
  del:    (path)       => request("DELETE", path),

  // --- auth -----------------------------------------------------------------
  requestOtp: (phone) => raw("POST", "/api/auth/otp/request", { phone })
    .then(({ response, payload }) => {
      if (!response.ok) {
        const e = payload?.error || {};
        throw new ApiError(response.status, e.code, e.message, e.details);
      }
      return payload;
    }),

  verifyOtp: async (phone, code, requestId) => {
    const { response, payload } = await raw("POST", "/api/auth/otp/verify", {
      phone, code, requestId, deviceName: deviceName(),
    });
    if (!response.ok) {
      const e = payload?.error || {};
      throw new ApiError(response.status, e.code, e.message, e.details);
    }
    auth.set(payload);
    return payload;
  },

  signOut: async () => {
    try { await request("POST", "/api/auth/logout"); } catch { /* leaving anyway */ }
    auth.clear();
  },

  // --- resources ------------------------------------------------------------
  me:            ()               => api.get("/api/me"),
  households:    ()               => api.get("/api/households"),
  createHousehold: (body)         => api.post("/api/households", body),
  members:       (hid)            => api.get(`/api/households/${hid}/members`),
  addMember:     (hid, body)      => api.post(`/api/households/${hid}/members`, body),
  taxonomy:      (hid)            => api.get(`/api/households/${hid}/taxonomy`),
  institutions:  (hid, q)         => api.get(`/api/households/${hid}/institutions${q ? `?q=${encodeURIComponent(q)}` : ""}`),
  investments:   (hid, query)     => api.get(`/api/households/${hid}/investments${query ? `?${query}` : ""}`),
  investment:    (hid, id)        => api.get(`/api/households/${hid}/investments/${id}`),
  capture:       (hid, body)      => api.post(`/api/households/${hid}/investments`, body),
  updateInvestment: (hid, id, b)  => api.patch(`/api/households/${hid}/investments/${id}`, b),
  setVisibility: (hid, id, b)     => api.patch(`/api/households/${hid}/investments/${id}/visibility`, b),
  addValuation:  (hid, id, b)     => api.post(`/api/households/${hid}/investments/${id}/valuations`, b),
  archive:       (hid, id)        => api.del(`/api/households/${hid}/investments/${id}`),
  restore:       (hid, id)        => api.post(`/api/households/${hid}/trash/investments/${id}/restore`),
  trash:         (hid)            => api.get(`/api/households/${hid}/trash`),
  dashboard:     (hid, scope, m)  => api.get(
    `/api/households/${hid}/dashboard?scope=${scope}${m ? `&member=${m}` : ""}`),
  invite:        (hid, body)      => api.post(`/api/households/${hid}/invitations`, body),

  // --- accounts -------------------------------------------------------------
  accounts:      (hid)            => api.get(`/api/households/${hid}/accounts`),
  account:       (hid, id)        => api.get(`/api/households/${hid}/accounts/${id}`),
  createAccount: (hid, body)      => api.post(`/api/households/${hid}/accounts`, body),
  updateAccount: (hid, id, body)  => api.patch(`/api/households/${hid}/accounts/${id}`, body),
  deleteAccount: (hid, id)        => api.del(`/api/households/${hid}/accounts/${id}`),
  revealNumber:  (hid, id)        => api.post(`/api/households/${hid}/accounts/${id}/reveal-number`),

  // --- step-up --------------------------------------------------------------
  stepUpStatus:  ()               => api.get("/api/auth/step-up"),
  stepUpRequest: ()               => api.post("/api/auth/step-up/request"),
  stepUpVerify:  (body)           => api.post("/api/auth/step-up/verify", body),

  // --- liabilities ----------------------------------------------------------
  liabilities:   (hid)            => api.get(`/api/households/${hid}/liabilities`),
  liability:     (hid, id)        => api.get(`/api/households/${hid}/liabilities/${id}`),
  createLiability: (hid, body)    => api.post(`/api/households/${hid}/liabilities`, body),
  updateLiability: (hid, id, b)   => api.patch(`/api/households/${hid}/liabilities/${id}`, b),
  recordBalance: (hid, id, body)  => api.post(`/api/households/${hid}/liabilities/${id}/balances`, body),
  setLiabilityVisibility: (hid, id, b) =>
    api.patch(`/api/households/${hid}/liabilities/${id}/visibility`, b),
  linkSecuredAsset: (hid, id, b)  => api.post(`/api/households/${hid}/liabilities/${id}/secured-by`, b),
  deleteLiability: (hid, id)      => api.del(`/api/households/${hid}/liabilities/${id}`),

  // --- nominees -------------------------------------------------------------
  // PUT, not PATCH: the nominee list is replaced as a unit.
  setNominees:   (hid, id, body)  =>
    api.put(`/api/households/${hid}/investments/${id}/nominees`, body),
  acceptInvite:  (token)          => api.post("/api/invitations/accept", { token }),
};

function deviceName() {
  const ua = navigator.userAgent;
  const browser = /Firefox/.test(ua) ? "Firefox"
    : /Edg/.test(ua) ? "Edge"
    : /Chrome/.test(ua) ? "Chrome"
    : /Safari/.test(ua) ? "Safari" : "Browser";
  const os = /Mac/.test(ua) ? "Mac" : /Win/.test(ua) ? "Windows"
    : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : "";
  return [browser, os].filter(Boolean).join(" on ");
}
