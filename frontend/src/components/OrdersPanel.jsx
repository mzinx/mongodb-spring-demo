import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

// One polymorphic `orders` collection. The selector filters by the `source`
// discriminator; "all" shows every source together (the whole point of the
// polymorphic single-collection model — no cross-collection union needed).
const SOURCES = [
  { key: 'all', label: 'All sources' },
  { key: 'web', label: 'Web store' },
  { key: 'pos', label: 'POS terminal' },
  { key: 'marketplace', label: 'Marketplace' },
]

// The UI-triggered workflow tasks. Each enrichment task $merges its own slice
// into the SAME order docs; `rollups` fans out into DIFFERENT collections.
const TASKS = [
  { key: 'payment', label: 'Payment', slice: 'payment' },
  { key: 'inventory', label: 'Inventory', slice: 'fulfillment' },
  { key: 'shipping', label: 'Shipping', slice: 'shipping' },
  { key: 'risk', label: 'Risk', slice: 'risk' },
]

/**
 * Orders page.
 *
 * Orders from every source live in ONE polymorphic `orders` collection
 * (discriminator `source`, shared core fields, per-source `sourceData`) — the
 * MongoDB polymorphic pattern. Generate orders per source, then run the
 * order-fulfillment workflow: each async task $merges its slice into the same
 * order documents (payment / fulfillment / shipping / risk), so you can watch a
 * document get built up from independent writers, step by step.
 */
export default function OrdersPanel({ events }) {
  const [source, setSource] = useState('all')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)
  const [data, setData] = useState({ content: [], bySource: [], total: 0 })

  const load = useCallback(
    (silent = false) => {
      const q = source === 'all' ? '' : `&source=${source}`
      return api
        .get(`/api/unified?limit=50${q}`)
        .then((d) => {
          setData(d)
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((e) => setError(e.message))
    },
    [source],
  )

  useEffect(() => {
    load()
  }, [load])

  // Everything lands in `orders`, kept live on /sync (message-queuing watches it)
  // and via the /cmd REFRESH the write + workflow endpoints broadcast.
  const onRefresh = useCallback(() => load(true), [load])
  useLiveRefresh(events, 'orders', onRefresh)

  const run = async (fn) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
      await load(true)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  // Workflow tasks are async on the server and return immediately; the merged
  // result arrives live over /sync, so we don't force an eager reload here.
  const runTask = async (task) => {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/api/workflow/run/${task}`)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const runAll = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/workflow/run-all')
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const genSource = source === 'all' ? 'web' : source

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Orders</h2>
          <p className="hint">
            One polymorphic <code>orders</code> collection — every source (<code>web</code> / <code>pos</code> /{' '}
            <code>marketplace</code>) shares a common core schema, with source-specific fields under{' '}
            <code>sourceData</code>. Generate orders, then run the fulfillment workflow to watch each async task{' '}
            <strong>$merge</strong> its slice into the same documents.
          </p>
        </div>
        <div className="row-actions">
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {SOURCES.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
              </option>
            ))}
          </select>
          {refreshedAt && (
            <span className="pill ok" title="Triggered by a live /sync or /cmd REFRESH">
              live {refreshedAt.toLocaleTimeString()}
            </span>
          )}
        </div>
      </div>

      <div className="row-actions toolbar">
        <button className="primary" disabled={busy} onClick={() => run(() => api.post(`/api/channels/insert?channel=${genSource}&count=1`))}>
          Insert 1 ({genSource})
        </button>
        <button disabled={busy} onClick={() => run(() => api.post(`/api/channels/insert?channel=${genSource}&count=10`))}>
          Insert 10 ({genSource})
        </button>
        <button disabled={busy} onClick={() => run(() => api.post(`/api/channels/update-random?channel=${genSource}`))}>
          Update random
        </button>
        <button className="danger" disabled={busy} onClick={() => run(() => api.post(`/api/channels/delete-random?channel=${genSource}`))}>
          Delete random
        </button>
      </div>

      <div className="row-actions toolbar">
        <span className="hint" style={{ marginRight: 4 }}>Workflow ($merge into same doc):</span>
        {TASKS.map((t) => (
          <button key={t.key} disabled={busy} onClick={() => runTask(t.key)} title={`Sets ${t.slice}.* on every order`}>
            Run {t.label}
          </button>
        ))}
        <button className="primary" disabled={busy} onClick={runAll} title="Fire every task at once (concurrent merges)">
          Run all
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      <OrdersView data={data} />
    </div>
  )
}

// --- Polymorphic orders view --------------------------------------------------

function OrdersView({ data }) {
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
          <span className="metric-label">orders total</span>
        </div>
      </div>

      <table className="table">
        <thead>
          <tr>
            <th>Order id</th>
            <th>Source</th>
            <th>Customer</th>
            <th>Product</th>
            <th>Qty</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Enrichment (workflow $merge)</th>
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
              <td>{enrichmentTags(o)}</td>
            </tr>
          ))}
          {data.content.length === 0 && (
            <tr>
              <td colSpan={8} className="empty">
                No orders yet — insert some above.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  )
}

// Renders which workflow slices have merged into an order doc so far. Each slice
// is written by an independent async task; they accumulate on the same document.
function enrichmentTags(o) {
  const slices = [
    ['payment', o.payment && o.payment.status],
    ['fulfillment', o.fulfillment && o.fulfillment.warehouse],
    ['shipping', o.shipping && o.shipping.carrier],
    ['risk', o.risk && (o.risk.flagged ? 'FLAGGED' : `score ${o.risk.score}`)],
  ]
  const present = slices.filter(([, v]) => v != null)
  if (present.length === 0) return <span className="hint">— not enriched —</span>
  return present.map(([k, v]) => (
    <span key={k} className="tag" style={{ marginRight: 4 }}>
      {k}: {String(v)}
    </span>
  ))
}

function fmt(n) {
  return typeof n === 'number' ? n.toFixed(2) : '0.00'
}
