import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

const STATUS_ORDER = ['PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED']
const SUMMARY_COLLECTION = 'orderSummaries'

/** Sort summaries by day id, newest first (matches the /api/summary order). */
function sortByDayDesc(list) {
  return [...list].sort((a, b) => (a._id < b._id ? 1 : a._id > b._id ? -1 : 0))
}

/**
 * Applies a single /sync change-stream event (as broadcast by the message-queuing
 * live-data listener) to the current summaries, returning a new array. The event
 * content shape is: { op, k, db, coll, doc?, changes? }.
 *  - insert/replace: `doc` is the full summary document -> upsert by _id
 *  - delete:         remove the document whose _id === k
 *  - update:         we don't get the full doc; caller falls back to an API load
 */
function applySyncEvent(list, content) {
  const op = content.op
  if (op === 'insert' || op === 'replace') {
    const doc = content.doc
    if (!doc?._id) return list
    const rest = list.filter((s) => s._id !== doc._id)
    return sortByDayDesc([doc, ...rest])
  }
  if (op === 'delete') {
    return list.filter((s) => s._id !== content.k)
  }
  return list
}

/**
 * Daily order summary dashboard.
 *
 * The data is NOT aggregated on page load: it is precomputed into the
 * `orderSummaries` collection by the `orderSummaryListener` change stream
 * listener (stream `order-summary`, AUTO_RECOVER mode - only the elected
 * leader instance recomputes). The summary collection is in
 * `messaging.watch-collections`, so the live-data service pushes the changed
 * summary documents on `/sync`.
 *
 * Rather than re-fetching the whole list from the API on every change, this
 * page applies those `/sync` payloads directly to the in-memory view: the
 * initial state is loaded once from `/api/summary`, then each `/sync` event
 * upserts/removes the affected day. An UPDATE event (no full document) falls
 * back to a one-off API reload, which normally never happens here because the
 * recompute uses `$merge ... whenMatched: replace` (producing replace ops).
 */
export default function DashboardPanel({ events }) {
  const [summaries, setSummaries] = useState([])
  const [streamStatus, setStreamStatus] = useState(null)
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)
  // Id of the most recent event we've already applied, so we only process new
  // /sync events (events[] is newest-first and shared across the whole app).
  const lastEventId = useRef(0)

  const load = useCallback(
    (silent = false) =>
      Promise.all([
        api.get('/api/summary'),
        api.get('/api/streams/order-summary/status').catch(() => null),
      ])
        .then(([data, status]) => {
          setSummaries(sortByDayDesc(data || []))
          setStreamStatus(status)
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((err) => setError(err.message)),
    [],
  )

  useEffect(() => {
    load()
  }, [load])

  // The summary data now updates from /sync, but the AUTO_RECOVER stream status
  // (which instance is leader / where it's running) isn't part of that feed, so
  // poll it periodically to keep the header line current.
  useEffect(() => {
    let alive = true
    const timer = setInterval(() => {
      api
        .get('/api/streams/order-summary/status')
        .then((status) => alive && setStreamStatus(status))
        .catch(() => {})
    }, 10000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])

  // Real-time: apply the changed summary documents that arrive on /sync directly
  // to the view, instead of reloading everything from the API.
  useEffect(() => {
    // Collect unprocessed /sync events for our collection, oldest first.
    const fresh = []
    for (const e of events) {
      if (e.id <= lastEventId.current) break // events are newest-first
      if (e.channel !== '/sync') continue
      const content = e.payload?.content
      if (content?.coll === SUMMARY_COLLECTION) fresh.push(e)
    }
    if (events.length) lastEventId.current = events[0].id
    if (fresh.length === 0) return

    fresh.reverse() // apply in chronological order
    // If any event is an UPDATE (no full doc), reload once to stay correct.
    if (fresh.some((e) => e.payload.content.op === 'update')) {
      load(true)
      return
    }
    setSummaries((prev) => {
      let next = prev
      for (const e of fresh) next = applySyncEvent(next, e.payload.content)
      return next
    })
    setRefreshedAt(new Date())
  }, [events, load])

  const recompute = () =>
    api.post('/api/summary/recompute').catch((err) => setError(err.message))

  const today = new Date().toISOString().slice(0, 10)
  const todaySummary = summaries.find((s) => s._id === today)
  const maxRevenue = Math.max(...summaries.map((s) => s.revenue ?? 0), 1)

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Daily order summary</h2>
          <p className="hint">
            Precomputed into <code>orderSummaries</code> by the <code>order-summary</code> change stream
            (AUTO_RECOVER: leader{' '}
            <strong>{streamStatus?.leader ?? 'electing…'}</strong>
            {streamStatus ? (streamStatus.running ? ', running on this instance' : ', running elsewhere') : ''}
            ) executing the <code>orders-daily-summary</code> pipeline template with <code>$merge</code>.
          </p>
        </div>
        <div className="row-actions">
          {refreshedAt && (
            <span className="pill ok" title="Applied from changed documents pushed on /sync">
              live-updated {refreshedAt.toLocaleTimeString()}
            </span>
          )}
          <button onClick={recompute}>Recompute now</button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="cards">
        <Metric label={`Orders today (${today})`} value={todaySummary?.orders ?? 0} />
        <Metric label="Revenue today" value={fmt(todaySummary?.revenue)} />
        <Metric label="Avg order value today" value={fmt(todaySummary?.avgOrderValue)} />
        <Metric label="Days tracked" value={summaries.length} />
      </div>

      <table className="table">
        <thead>
          <tr>
            <th>Day</th>
            <th>Orders</th>
            <th>Revenue</th>
            <th>Avg order</th>
            <th>By status</th>
            <th>Computed at</th>
          </tr>
        </thead>
        <tbody>
          {summaries.map((s) => (
            <tr key={s._id}>
              <td className="mono">{s._id}</td>
              <td>{s.orders}</td>
              <td>
                <div className="bar-cell">
                  <span className="bar" style={{ width: `${((s.revenue ?? 0) / maxRevenue) * 100}%` }} />
                  <span>{fmt(s.revenue)}</span>
                </div>
              </td>
              <td>{fmt(s.avgOrderValue)}</td>
              <td>
                {STATUS_ORDER.filter((st) => s.byStatus?.[st]).map((st) => (
                  <span key={st} className={`tag status-${st}`} style={{ marginRight: 4 }}>
                    {st} {s.byStatus[st]}
                  </span>
                ))}
              </td>
              <td className="hint">{s.updatedAt ? new Date(s.updatedAt).toLocaleTimeString() : ''}</td>
            </tr>
          ))}
          {summaries.length === 0 && (
            <tr>
              <td colSpan={6} className="empty">
                No summaries yet — insert some orders on the Orders page.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function Metric({ label, value }) {
  return (
    <div className="card metric">
      <span className="metric-value">{value}</span>
      <span className="metric-label">{label}</span>
    </div>
  )
}

function fmt(n) {
  return typeof n === 'number' ? n.toFixed(2) : '0.00'
}
