// Thin JSON fetch helpers for the demo backend API.

async function request(method, url, body) {
  const res = await fetch(url, {
    method,
    // Send/receive the Spring Session cookie so the backend can identify this
    // browser (drives the messaging presence list).
    credentials: 'include',
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    let detail = ''
    try {
      detail = await res.text()
    } catch {
      /* ignore */
    }
    throw new Error(`${method} ${url} failed (${res.status}) ${detail}`)
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
