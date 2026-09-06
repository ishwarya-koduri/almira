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
  return payload;
}

export const api = {
  get:    (path)       => request("GET", path),
  post:   (path, body) => request("POST", path, body),
  patch:  (path, body) => request("PATCH", path, body),
  put:    (path, body) => request("PUT", path, body),
  del:    (path)       => request("DELETE", path),

  // --- auth -----------------------------------------------------------------
  requestOtp: (phone) => raw("POST", "/api/v1/auth/otp/request", { phone })
    .then(({ response, payload }) => {
      if (!response.ok) {
        const e = payload?.error || {};
        throw new ApiError(response.status, e.code, e.message, e.details);
      }
      return payload;
    }),

  verifyOtp: async (phone, code, requestId) => {
    const { response, payload } = await raw("POST", "/api/v1/auth/otp/verify", {
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
  archive:       (hid, id)        => api.del(`/api/v1/households/${hid}/investments/${id}`),
  restore:       (hid, id)        => api.post(`/api/v1/households/${hid}/trash/investments/${id}/restore`),
  trash:         (hid)            => api.get(`/api/v1/households/${hid}/trash`),
  dashboard:     (hid, scope, m)  => api.get(
    `/api/v1/households/${hid}/dashboard?scope=${scope}${m ? `&member=${m}` : ""}`),
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

  // --- nominees -------------------------------------------------------------
  // PUT, not PATCH: the nominee list is replaced as a unit.
  setNominees:   (hid, id, body)  =>
    api.put(`/api/v1/households/${hid}/investments/${id}/nominees`, body),
  acceptInvite:  (token)          => api.post("/api/v1/invitations/accept", { token }),

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

  // --- zero-knowledge mode --------------------------------------------------
  e2eStatus:     (hid)            => api.get(`/api/v1/households/${hid}/e2e`),
  putE2eKey:     (hid, body)      => api.put(`/api/v1/households/${hid}/e2e/key`, body),
  sealedValues:  (hid, type, id)  => api.get(
    `/api/v1/households/${hid}/e2e/values${type ? `?recordType=${type}&recordId=${id}` : ""}`),
  sealValue:     (hid, type, id, field, body) =>
    api.put(`/api/v1/households/${hid}/e2e/values/${type}/${id}/${field}`, body),
  unsealValue:   (hid, type, id, field) =>
    api.del(`/api/v1/households/${hid}/e2e/values/${type}/${id}/${field}`),

  // --- estate, contacts and continuity --------------------------------------
  contacts:      (hid, query)     => api.get(`/api/v1/households/${hid}/contacts${query ? `?${query}` : ""}`),
  createContact: (hid, body)      => api.post(`/api/v1/households/${hid}/contacts`, body),
  linkContact:   (hid, id, body)  => api.post(`/api/v1/households/${hid}/contacts/${id}/links`, body),
  deleteContact: (hid, id)        => api.del(`/api/v1/households/${hid}/contacts/${id}`),
  estateDocuments: (hid)          => api.get(`/api/v1/households/${hid}/estate/documents`),
  createEstateDocument: (hid, b)  => api.post(`/api/v1/households/${hid}/estate/documents`, b),
  mismatches:    (hid)            => api.get(`/api/v1/households/${hid}/estate/mismatches`),
  transmission:  (hid, id)        => api.get(`/api/v1/households/${hid}/continuity/transmission/${id}`),
  handbook:      (hid)            => api.get(`/api/v1/households/${hid}/continuity/handbook`),
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

  // --- documents ------------------------------------------------------------
  documents:     (hid)            => api.get(`/api/v1/households/${hid}/documents`),
  documentAccess: (hid, id)       => api.post(`/api/v1/households/${hid}/documents/${id}/access`),
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
  return payload;
}

/**
 * A download that carries the Authorization header — an ordinary link cannot,
 * and the export endpoint is authenticated like everything else.
 */
export async function downloadAuthenticated(path, fallbackName) {
  if (!accessToken && auth.refreshToken) await refreshTokens();
  const response = await fetch(path, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (!response.ok) throw new ApiError(response.status, "download_failed", "That download didn't work.");
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
