import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

const STATUS_ORDER = ['PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED']

/**
 * Daily order summary dashboard.
 *
 * The data is NOT aggregated on page load: it is precomputed into the
 * `orderSummaries` collection by the `orderSummaryListener` change stream
 * listener (stream `order-summary`, AUTO_RECOVER mode - only the elected
 * leader instance recomputes). The summary collection is itself watched by
 * the live-data service, so this page refreshes in real time whenever the
 * summaries change.
 */
export default function DashboardPanel({ events }) {
  const [summaries, setSummaries] = useState([])
  const [streamStatus, setStreamStatus] = useState(null)
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)

  const load = useCallback(
    (silent = false) =>
      Promise.all([
        api.get('/api/summary'),
        api.get('/api/streams/order-summary/status').catch(() => null),
      ])
        .then(([data, status]) => {
          setSummaries(data)
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

  // Real-time: the summary collection is in messaging.watch-collections, so a
  // REFRESH command arrives on /cmd whenever the listener recomputed it.
  const onRefresh = useCallback(() => load(true), [load])
  useLiveRefresh(events, 'orderSummaries', onRefresh)

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
            <span className="pill ok" title="Triggered by a REFRESH command on /cmd">
              live-refreshed {refreshedAt.toLocaleTimeString()}
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
