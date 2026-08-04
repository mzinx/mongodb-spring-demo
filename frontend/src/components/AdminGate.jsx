import { useState } from 'react'
import { api, setAdminCredentials, clearAdminCredentials, hasAdminCredentials } from '../api.js'

/**
 * Login gate for the Admin tab. Collects a username / password, sets them as
 * the HTTP Basic credentials used by the guarded /api/streams, /api/pipelines
 * and /api/aggregations endpoints, and verifies them against the backend
 * before revealing the admin panels. The credentials themselves live only in
 * memory (see api.js) — reloading the page requires signing in again.
 */
export default function AdminGate({ children }) {
  const [authed, setAuthed] = useState(hasAdminCredentials())
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const signIn = async (e) => {
    e.preventDefault()
    setError(null)
    setBusy(true)
    setAdminCredentials(username, password)
    try {
      // Guarded endpoint: 204 => credentials accepted, 401 => rejected.
      await api.get('/api/admin/verify')
      setAuthed(true)
    } catch (err) {
      clearAdminCredentials()
      setError(err.status === 401 ? 'Invalid username or password.' : err.message)
    } finally {
      setBusy(false)
    }
  }

  const signOut = () => {
    clearAdminCredentials()
    setAuthed(false)
    setUsername('')
    setPassword('')
  }

  if (authed) {
    return (
      <>
        <div className="admin-bar">
          <span className="pill ok">
            <span className="dot" /> admin authenticated
          </span>
          <button onClick={signOut}>Sign out</button>
        </div>
        {children}
      </>
    )
  }

  return (
    <div className="panel">
      <div className="card admin-login">
        <h2>Admin sign in</h2>
        <p className="hint">
          The Admin tools (change streams, pipeline templates, ad-hoc aggregations) are protected.
          Enter the admin credentials configured via the <code>ADMIN_USERNAME</code> /{' '}
          <code>ADMIN_PASSWORD</code> environment variables.
        </p>
        <form onSubmit={signIn}>
          <label>
            Username
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          {error && <p className="error">{error}</p>}
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>
              {busy ? 'Signing in…' : 'Sign in'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
