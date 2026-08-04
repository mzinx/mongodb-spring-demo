// Thin JSON fetch helpers for the demo backend API.

// Admin (HTTP Basic) credentials for the guarded /api/streams, /api/pipelines
// and /api/aggregations endpoints. Held in module state and set by the Admin
// login gate; the raw password is never persisted, only the base64 header.
let adminAuthHeader = null

export function setAdminCredentials(username, password) {
  adminAuthHeader = username && password ? `Basic ${btoa(`${username}:${password}`)}` : null
}

export function clearAdminCredentials() {
  adminAuthHeader = null
}

export function hasAdminCredentials() {
  return Boolean(adminAuthHeader)
}

// Endpoints that are guarded by admin Basic auth on the backend.
const ADMIN_PREFIXES = ['/api/streams', '/api/pipelines', '/api/aggregations', '/api/admin']
const isAdminUrl = (url) => ADMIN_PREFIXES.some((p) => url.startsWith(p))

async function request(method, url, body) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  // Attach the admin Authorization header only for guarded endpoints.
  if (adminAuthHeader && isAdminUrl(url)) headers['Authorization'] = adminAuthHeader

  const res = await fetch(url, {
    method,
    // Send/receive the Spring Session cookie so the backend can identify this
    // browser (drives the messaging presence list).
    credentials: 'include',
    headers: Object.keys(headers).length ? headers : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    let detail = ''
    try {
      detail = await res.text()
    } catch {
      /* ignore */
    }
    const err = new Error(`${method} ${url} failed (${res.status}) ${detail}`)
    err.status = res.status
    throw err
  }
  if (res.status === 204) return null
  return res.json()
}

export const api = {
  get: (url) => request('GET', url),
  post: (url, body) => request('POST', url, body),
  put: (url, body) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
}
