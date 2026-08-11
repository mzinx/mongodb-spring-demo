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
 * Read-only daily order summary dashboard.
 *
 * The data is NOT aggregated on page load: it is precomputed into the
 * `orderSummaries` collection by the `order-summary` materialized-view change
 * stream (AUTO_RECOVER mode). This app seeds that stream's config
 * (DemoDataSeeder); the companion <em>mongostream</em> app executes it against
 * the shared database. This demo only reads and live-updates the view. Because
 * `orderSummaries` is in `messaging.watch-collections`, the live-data service
 * pushes the changed summary documents on `/sync`.
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
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)
  // Id of the most recent event we've already applied, so we only process new
  // /sync events (events[] is newest-first and shared across the whole app).
  const lastEventId = useRef(0)

  const load = useCallback(
    (silent = false) =>
      api
        .get('/api/summary')
        .then((data) => {
          setSummaries(sortByDayDesc(data || []))
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((err) => setError(err.message)),
    [],
  )

  useEffect(() => {
    load()
  }, [load])

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

  const today = new Date().toISOString().slice(0, 10)
  const todaySummary = summaries[0]//summaries.find((s) => s._id === today)
  const maxRevenue = Math.max(...summaries.map((s) => s.revenue ?? 0), 1)

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Daily order summary</h2>
          <p className="hint">
            Read-only view precomputed into <code>orderSummaries</code> by the companion{' '}
            <strong>mongostream</strong> app (its <code>order-summary</code> materialized-view change
            stream running <code>$merge</code>) on the same database. Insert orders on the{' '}
            <strong>Orders</strong> page and watch this update live over <code>/sync</code>.
          </p>
        </div>
        <div className="row-actions">
          {refreshedAt && (
            <span className="pill ok" title="Applied from changed documents pushed on /sync">
              live-updated {refreshedAt.toLocaleTimeString()}
            </span>
          )}
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="cards">
        <Metric label={`Orders today (${todaySummary?._id})`} value={todaySummary?.orders ?? 0} />
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
