import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

const PERIODS = [
  { key: 'day', label: 'Daily', collection: 'ordersByDay' },
  { key: 'week', label: 'Weekly', collection: 'ordersByWeek' },
  { key: 'month', label: 'Monthly', collection: 'ordersByMonth' },
]

// Preferred display order for the per-status breakdown; any other statuses
// present in the data are appended alphabetically after these.
const STATUS_ORDER = [
  'PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED',
  'OPEN', 'REFUNDED', 'VOID', 'AWAITING', 'DISPATCHED', 'COMPLETED', 'RETURNED',
]

/** Orders the keys of a byStatus map: known statuses first, then the rest. */
function orderedStatuses(byStatus) {
  const keys = Object.keys(byStatus || {})
  const known = STATUS_ORDER.filter((s) => keys.includes(s))
  const extra = keys.filter((s) => !STATUS_ORDER.includes(s)).sort()
  return [...known, ...extra]
}

/**
 * Order summary dashboard, bucketed by period.
 *
 * The data is NOT aggregated on page load: it is precomputed from `unifiedOrders`
 * by the `orders-by-day`, `orders-by-week` and `orders-by-month` materialized-view
 * change streams (each a `$dateTrunc` rollup + `$merge`) into three DISTINCT
 * collections — `ordersByDay`, `ordersByWeek`, `ordersByMonth`. This page reads
 * the collection for the selected granularity and live-refreshes on that
 * collection's changes (pushed via /cmd).
 */
export default function DashboardPanel({ events }) {
  const [period, setPeriod] = useState('day')
  const [buckets, setBuckets] = useState([])
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)

  const load = useCallback(
    (silent = false) =>
      api
        .get(`/api/periods?period=${period}`)
        .then((d) => {
          setBuckets(d.content || [])
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((e) => setError(e.message)),
    [period],
  )

  useEffect(() => {
    load()
  }, [load])

  const onRefresh = useCallback(() => load(true), [load])
  // Refresh when the collection for the *selected* period changes.
  const activeCollection = PERIODS.find((p) => p.key === period)?.collection ?? 'ordersByDay'
  useLiveRefresh(events, activeCollection, onRefresh)

  // Newest bucket first (the API already sorts by bucketStart desc).
  const latest = buckets[0]
  const totalOrders = buckets.reduce((a, b) => a + (b.orders ?? 0), 0)
  const totalRevenue = buckets.reduce((a, b) => a + (b.revenue ?? 0), 0)
  const maxRevenue = Math.max(...buckets.map((b) => b.revenue ?? 0), 1)
  const periodLabel = PERIODS.find((p) => p.key === period)?.label ?? period

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Order summary by period</h2>
          <p className="hint">
            Precomputed from <code>unifiedOrders</code> (the merge of all three channels) by{' '}
            <code>$dateTrunc</code> rollup streams into three collections —{' '}
            <code>ordersByDay</code> / <code>ordersByWeek</code> / <code>ordersByMonth</code>. Generate orders on
            the <strong>Orders</strong> tab and watch buckets update live over <code>/sync</code>.
          </p>
        </div>
        <div className="row-actions">
          <div className="row-actions toolbar">
            {PERIODS.map((p) => (
              <button key={p.key} className={period === p.key ? 'primary' : ''} onClick={() => setPeriod(p.key)}>
                {p.label}
              </button>
            ))}
          </div>
          {refreshedAt && (
            <span className="pill ok" title="Applied from a REFRESH on /cmd">
              live {refreshedAt.toLocaleTimeString()}
            </span>
          )}
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="cards">
        <Metric label={`Latest ${period} orders (${bucketLabel(latest)})`} value={latest?.orders ?? 0} />
        <Metric label={`Latest ${period} revenue`} value={fmt(latest?.revenue)} />
        <Metric label="Latest avg order value" value={fmt(latest?.avgOrderValue)} />
        <Metric label={`${periodLabel} buckets`} value={buckets.length} />
        <Metric label="Orders (all buckets)" value={totalOrders} />
        <Metric label="Revenue (all buckets)" value={fmt(totalRevenue)} />
      </div>

      <table className="table">
        <thead>
          <tr>
            <th>{periodLabel} bucket</th>
            <th>Orders</th>
            <th>Revenue</th>
            <th>Avg order</th>
            <th>Units</th>
            <th>By status</th>
            <th>By product</th>
            <th>Computed at</th>
          </tr>
        </thead>
        <tbody>
          {buckets.map((b) => (
            <tr key={b._id}>
              <td className="mono">{bucketLabel(b)}</td>
              <td>{b.orders}</td>
              <td>
                <div className="bar-cell">
                  <span className="bar" style={{ width: `${((b.revenue ?? 0) / maxRevenue) * 100}%` }} />
                  <span>{fmt(b.revenue)}</span>
                </div>
              </td>
              <td>{fmt(b.avgOrderValue)}</td>
              <td>{b.units}</td>
              <td>
                {orderedStatuses(b.byStatus).map((st) => (
                  <span key={st} className={`tag status-${st}`} style={{ marginRight: 4 }}>
                    {st} {b.byStatus[st]}
                  </span>
                ))}
              </td>
              <td>
                {(b.byProduct || []).map((p) => (
                  <span key={p.product} className="tag" style={{ marginRight: 4 }}>
                    {p.product} {p.orders}
                  </span>
                ))}
              </td>
              <td className="hint">{b.updatedAt ? new Date(b.updatedAt).toLocaleTimeString() : ''}</td>
            </tr>
          ))}
          {buckets.length === 0 && (
            <tr>
              <td colSpan={8} className="empty">
                No {period} buckets yet — generate orders on the Orders tab.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function bucketLabel(b) {
  if (!b) return '—'
  if (b.bucketStart) return new Date(b.bucketStart).toISOString().slice(0, 10)
  // _id is "<period>|<ISO>"; fall back to the date part after the pipe.
  const s = String(b._id)
  const pipe = s.indexOf('|')
  return pipe >= 0 ? s.slice(pipe + 1, pipe + 11) : s
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
