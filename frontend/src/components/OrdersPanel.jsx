import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

// The channel selector. "unified" is the read-only consolidated view; the three
// source channels are writable (each has its own differently-shaped collection).
const CHANNELS = [
  { key: 'unified', label: 'Unified (all sources)', coll: 'unifiedOrders', writable: false },
  { key: 'web', label: 'Web store', coll: 'webOrders', writable: true },
  { key: 'pos', label: 'POS terminal', coll: 'posOrders', writable: true },
  { key: 'marketplace', label: 'Marketplace', coll: 'marketplaceOrders', writable: true },
]

/**
 * Orders page. A channel dropdown selects between:
 *  - "unified": the read-only consolidated `unifiedOrders` view — the merge of
 *    all three source channels, kept current incrementally by the per-source
 *    changeMirror streams (one upsert per event). Also shows the per-source mix.
 *  - a source channel (web / pos / marketplace): its raw, differently-shaped
 *    documents, with generator buttons that produce change events.
 *
 * Every write ultimately lands in `unifiedOrders`, so the page live-refreshes on
 * that collection's REFRESH command regardless of the selected channel.
 */
export default function OrdersPanel({ events }) {
  const [channel, setChannel] = useState('unified')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)

  // Unified view state
  const [unified, setUnified] = useState({ content: [], bySource: [], total: 0 })
  // Raw channel view state
  const [raw, setRaw] = useState({ content: [], total: 0 })

  const selected = CHANNELS.find((c) => c.key === channel) || CHANNELS[0]

  const load = useCallback(
    (silent = false) => {
      const done = () => {
        if (silent) setRefreshedAt(new Date())
        setError(null)
      }
      if (channel === 'unified') {
        return api
          .get('/api/unified?limit=50')
          .then((d) => {
            setUnified(d)
            done()
          })
          .catch((e) => setError(e.message))
      }
      return api
        .get(`/api/channels/list?channel=${channel}&limit=25`)
        .then((d) => {
          setRaw(d)
          done()
        })
        .catch((e) => setError(e.message))
    },
    [channel],
  )

  useEffect(() => {
    load()
  }, [load])

  // Live-refresh off the collection backing the CURRENT view:
  //  - unified view -> the derived `unifiedOrders` collection, kept live on /sync
  //    (message-queuing watches it), so it updates as the mirror streams catch up.
  //  - a raw channel view -> that channel's own collection (e.g. `webOrders`),
  //    refreshed by the /cmd REFRESH the write endpoint broadcasts, so every
  //    client's raw list updates immediately when anyone writes that channel.
  const onRefresh = useCallback(() => load(true), [load])
  useLiveRefresh(events, selected.coll, onRefresh)

  const run = async (fn) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
      // Reload the raw channel view immediately; the unified view also updates
      // via the live REFRESH, but a manual reload keeps the raw list snappy.
      await load(true)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Orders</h2>
          <p className="hint">
            Three source channels (<code>webOrders</code> / <code>posOrders</code> / <code>marketplaceOrders</code>),
            each a <strong>different shape</strong>, are consolidated incrementally into <code>unifiedOrders</code> by
            per-source change streams. Pick a channel to generate orders, or view the unified merge.
          </p>
        </div>
        <div className="row-actions">
          <select value={channel} onChange={(e) => setChannel(e.target.value)}>
            {CHANNELS.map((c) => (
              <option key={c.key} value={c.key}>
                {c.label}
              </option>
            ))}
          </select>
          {refreshedAt && (
            <span className="pill ok" title="Triggered by a REFRESH command on /cmd">
              live {refreshedAt.toLocaleTimeString()}
            </span>
          )}
        </div>
      </div>

      {selected.writable && (
        <div className="row-actions toolbar">
          <button className="primary" disabled={busy} onClick={() => run(() => api.post(`/api/channels/insert?channel=${channel}&count=1`))}>
            Insert 1
          </button>
          <button disabled={busy} onClick={() => run(() => api.post(`/api/channels/insert?channel=${channel}&count=10`))}>
            Insert 10
          </button>
          <button disabled={busy} onClick={() => run(() => api.post(`/api/channels/update-random?channel=${channel}`))}>
            Update random
          </button>
          <button className="danger" disabled={busy} onClick={() => run(() => api.post(`/api/channels/delete-random?channel=${channel}`))}>
            Delete random
          </button>
        </div>
      )}

      {error && <p className="error">{error}</p>}

      {channel === 'unified' ? (
        <UnifiedView data={unified} />
      ) : (
        <RawChannelView channel={selected} data={raw} />
      )}
    </div>
  )
}

// --- Unified consolidated view ------------------------------------------------

function UnifiedView({ data }) {
  return (
    <>
      <div className="cards">
        {data.bySource.map((s) => (
          <div className="card metric" key={s._id}>
            <span className="metric-value">{s.count}</span>
            <span className="metric-label">{s._id || 'unknown'}</span>
          </div>
        ))}
        <div className="card metric">
          <span className="metric-value">{data.total}</span>
          <span className="metric-label">unified total</span>
        </div>
      </div>

      <table className="table">
        <thead>
          <tr>
            <th>Unified id</th>
            <th>Source</th>
            <th>Customer</th>
            <th>Product</th>
            <th>Qty</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {data.content.map((o) => (
            <tr key={o._id}>
              <td className="mono">{o._id}</td>
              <td>
                <span className="tag">{o.source}</span>
              </td>
              <td>{o.customer}</td>
              <td>{o.product}</td>
              <td>{o.quantity}</td>
              <td>{fmt(o.amount)}</td>
              <td>
                <span className="tag">{o.status}</span>
              </td>
              <td className="hint">{o.createdAt ? new Date(o.createdAt).toLocaleString() : ''}</td>
            </tr>
          ))}
          {data.content.length === 0 && (
            <tr>
              <td colSpan={8} className="empty">
                No unified orders yet — pick a source channel and generate some.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  )
}

// --- Raw source-channel view (shows the differing native shapes) --------------

function RawChannelView({ channel, data }) {
  return (
    <>
      <p className="hint">
        Raw <code>{channel.coll}</code> documents (native shape). These are normalized per-event into the unified
        schema and mirrored into <code>unifiedOrders</code>. Total: {data.total}.
      </p>
      <table className="table">
        <thead>
          <tr>
            <th>Id</th>
            <th>Document (native shape)</th>
          </tr>
        </thead>
        <tbody>
          {data.content.map((o) => (
            <tr key={o._id}>
              <td className="mono">{String(o._id).slice(-10)}</td>
              <td>
                <code style={{ whiteSpace: 'pre-wrap', fontSize: '0.8em' }}>{JSON.stringify(stripId(o))}</code>
              </td>
            </tr>
          ))}
          {data.content.length === 0 && (
            <tr>
              <td colSpan={2} className="empty">
                No {channel.label} orders yet — insert some above.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  )
}

function stripId(doc) {
  const { _id, ...rest } = doc
  return rest
}

function fmt(n) {
  return typeof n === 'number' ? n.toFixed(2) : '0.00'
}
