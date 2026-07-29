import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import StreamForm from './StreamForm.jsx'

/**
 * Change stream lifecycle dashboard: lists persisted definitions merged with
 * their live runtime status, and offers create / configure / start / stop /
 * delete actions.
 */
export default function StreamsPanel() {
  const [configs, setConfigs] = useState([])
  const [statuses, setStatuses] = useState([])
  const [listeners, setListeners] = useState([])
  const [editing, setEditing] = useState(null) // null | 'new' | config object
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const [cfg, st, ls] = await Promise.all([
        api.get('/api/streams'),
        api.get('/api/streams/status'),
        api.get('/api/streams/listeners'),
      ])
      setConfigs(cfg)
      setStatuses(st)
      setListeners(ls)
      setError(null)
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [load])

  const action = async (fn) => {
    try {
      await fn()
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  const statusById = Object.fromEntries(statuses.map((s) => [s.id, s]))
  const configIds = new Set(configs.map((c) => c.id))
  const internalStatuses = statuses.filter((s) => !configIds.has(s.id))

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Change streams</h2>
          <p className="hint">
            Definitions are stored in <code>_changeStreamConfigs</code>; the library reconciles running
            streams every <code>change-stream.config-refresh-interval</code> (10s in this demo), so
            lifecycle actions take a few seconds to apply.
          </p>
        </div>
        {!editing && (
          <button className="primary" onClick={() => setEditing('new')}>
            + New stream
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      {editing && (
        <StreamForm
          initial={editing === 'new' ? null : editing}
          listeners={listeners}
          onSaved={() => {
            setEditing(null)
            load()
          }}
          onCancel={() => setEditing(null)}
        />
      )}

      <table className="table">
        <thead>
          <tr>
            <th>Id</th>
            <th>Collection</th>
            <th>Mode</th>
            <th>Listener</th>
            <th>Resume</th>
            <th>State</th>
            <th>Leader / term</th>
            <th>Members (epoch)</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {configs.map((cfg) => {
            const st = statusById[cfg.id]
            return (
              <tr key={cfg.id}>
                <td className="mono">{cfg.id}</td>
                <td className="mono">{cfg.collectionName ?? <em>whole db</em>}</td>
                <td>{cfg.mode}</td>
                <td className="mono">{cfg.listener}</td>
                <td>{cfg.resumeStrategy}</td>
                <td>
                  <StateBadge enabled={cfg.enabled} running={st?.running} />
                </td>
                <td className="mono">
                  {st?.leader ? `${st.leader} (t${st.term})` : '—'}
                </td>
                <td className="mono">
                  {st?.instances?.length ? `${st.instances.join(', ')} (e${st.epoch})` : '—'}
                </td>
                <td className="row-actions">
                  {cfg.enabled ? (
                    <button onClick={() => action(() => api.post(`/api/streams/${cfg.id}/stop`))}>Stop</button>
                  ) : (
                    <button onClick={() => action(() => api.post(`/api/streams/${cfg.id}/start`))}>Start</button>
                  )}
                  <button onClick={() => setEditing(cfg)}>Edit</button>
                  <button
                    className="danger"
                    onClick={() => confirm(`Delete stream "${cfg.id}"?`) && action(() => api.del(`/api/streams/${cfg.id}`))}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            )
          })}
          {internalStatuses.map((st) => (
            <tr key={st.id} className="muted">
              <td className="mono">
                {st.id} <span className="tag">internal</span>
              </td>
              <td className="mono">{st.collectionName ?? <em>whole db</em>}</td>
              <td>{st.mode}</td>
              <td className="mono">{st.listener}</td>
              <td>{st.resumeStrategy}</td>
              <td>
                <StateBadge enabled running={st.running} />
              </td>
              <td className="mono">{st.leader ? `${st.leader} (t${st.term})` : '—'}</td>
              <td className="mono">{st.instances?.length ? `${st.instances.join(', ')} (e${st.epoch})` : '—'}</td>
              <td />
            </tr>
          ))}
          {configs.length === 0 && internalStatuses.length === 0 && (
            <tr>
              <td colSpan={9} className="empty">
                No change streams yet — create one above.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function StateBadge({ enabled, running }) {
  if (!enabled) return <span className="state stopped">stopped</span>
  if (running) return <span className="state running">running</span>
  return <span className="state pending">starting…</span>
}
