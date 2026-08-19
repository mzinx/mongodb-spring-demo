import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

// All granularities live in ONE `ordersByPeriod` collection (day/week/month
// computed in a single $unionWith pass), so every period reads/refreshes off the
// same collection and just filters by `period`.
const PERIOD_COLLECTION = 'ordersByPeriod'
const PERIODS = [
  { key: 'day', label: 'Daily' },
  { key: 'week', label: 'Weekly' },
  { key: 'month', label: 'Monthly' },
]

const ROLLUPS = [
  { key: 'customer', label: 'By customer', collection: 'customerSummary', keyLabel: 'Customer' },
  { key: 'product', label: 'By product', collection: 'productInventory', keyLabel: 'Product' },
]

// Preferred display order for the per-status breakdown; any other statuses
// present in the data are appended alphabetically after these.
const STATUS_ORDER = ['PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED']

/** Orders the keys of a byStatus map: known statuses first, then the rest. */
function orderedStatuses(byStatus) {
  const keys = Object.keys(byStatus || {})
  const known = STATUS_ORDER.filter((s) => keys.includes(s))
  const extra = keys.filter((s) => !STATUS_ORDER.includes(s)).sort()
  return [...known, ...extra]
}

/**
 * Dashboard with two views, both precomputed via $merge (never aggregated on
 * page load):
 *  - "By period": `orders` bucketed by day/week/month into one `ordersByPeriod`
 *    collection (a single `$unionWith` pass) by the `orders-by-period` stream.
 *  - "Rollups": one `rollup-orders` change stream fans out (via the listener's
 *    writeStages) to `customerSummary` and `productInventory`.
 * Each view live-refreshes on its backing collection's /sync changes.
 */
export default function DashboardPanel({ events }) {
  const [view, setView] = useState('period')
  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Order summary</h2>
          <p className="hint">
            Two precomputed <strong>$merge</strong> outputs: <em>by period</em> (day/week/month in one{' '}
            <code>ordersByPeriod</code> collection via a single <code>$unionWith</code> pass) and <em>rollups</em>{' '}
            (<code>customerSummary</code> / <code>productInventory</code>, both from one <code>rollup-orders</code> stream).
          </p>
        </div>
        <div className="row-actions toolbar">
          <button className={view === 'period' ? 'primary' : ''} onClick={() => setView('period')}>
            By period
          </button>
          <button className={view === 'rollups' ? 'primary' : ''} onClick={() => setView('rollups')}>
            Rollups
          </button>
        </div>
      </div>

      {view === 'period' ? <PeriodView events={events} /> : <RollupsView events={events} />}
    </div>
  )
}

// --- By-period view -----------------------------------------------------------

function PeriodView({ events }) {
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
  // All periods share one collection; any change to it refreshes the current view.
  useLiveRefresh(events, PERIOD_COLLECTION, onRefresh)

  const latest = buckets[0]
  const totalOrders = buckets.reduce((a, b) => a + (b.orders ?? 0), 0)
  const totalRevenue = buckets.reduce((a, b) => a + (b.revenue ?? 0), 0)
  const maxRevenue = Math.max(...buckets.map((b) => b.revenue ?? 0), 1)
  const periodLabel = PERIODS.find((p) => p.key === period)?.label ?? period

  return (
    <>
      <div className="row-actions toolbar">
        {PERIODS.map((p) => (
          <button key={p.key} className={period === p.key ? 'primary' : ''} onClick={() => setPeriod(p.key)}>
            {p.label}
          </button>
        ))}
        {refreshedAt && (
          <span className="pill ok" title="Applied from a live /sync change">
            live {refreshedAt.toLocaleTimeString()}
          </span>
        )}
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
    </>
  )
}

// --- Rollups view (workflow fan-out $merge) -----------------------------------

function RollupsView({ events }) {
  const [kind, setKind] = useState('customer')
  const [rows, setRows] = useState([])
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)

  const active = ROLLUPS.find((r) => r.key === kind) ?? ROLLUPS[0]

  const load = useCallback(
    (silent = false) =>
      api
        .get(`/api/workflow/rollups?kind=${kind}`)
        .then((d) => {
          setRows(d.content || [])
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((e) => setError(e.message)),
    [kind],
  )

  useEffect(() => {
    load()
  }, [load])

  const onRefresh = useCallback(() => load(true), [load])
  useLiveRefresh(events, active.collection, onRefresh)

  const maxRevenue = Math.max(...rows.map((r) => r.revenue ?? 0), 1)

  return (
    <>
      <div className="row-actions toolbar">
        {ROLLUPS.map((r) => (
          <button key={r.key} className={kind === r.key ? 'primary' : ''} onClick={() => setKind(r.key)}>
            {r.label}
          </button>
        ))}
        {refreshedAt && (
          <span className="pill ok" title="Applied from a live /sync change">
            live {refreshedAt.toLocaleTimeString()}
          </span>
        )}
      </div>

      <p className="hint">
        Kept live by the single <code>rollup-orders</code> change stream, which fans out (via the listener&apos;s
        <code>writeStages</code>) to both <code>customerSummary</code> and <code>productInventory</code>. Generate or
        edit orders on the Orders tab and watch <code>{active.collection}</code> update.
      </p>

      {error && <p className="error">{error}</p>}

      <table className="table">
        <thead>
          <tr>
            <th>{active.keyLabel}</th>
            <th>Orders</th>
            <th>Units</th>
            <th>Revenue</th>
            <th>Updated at</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r._id}>
              <td className="mono">{r._id}</td>
              <td>{r.orders}</td>
              <td>{r.units}</td>
              <td>
                <div className="bar-cell">
                  <span className="bar" style={{ width: `${((r.revenue ?? 0) / maxRevenue) * 100}%` }} />
                  <span>{fmt(r.revenue)}</span>
                </div>
              </td>
              <td className="hint">{r.updatedAt ? new Date(r.updatedAt).toLocaleTimeString() : ''}</td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={5} className="empty">
                No rollups yet — generate orders on the Orders tab.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  )
}

function bucketLabel(b) {
  if (!b) return '—'
  if (b.bucketStart) return new Date(b.bucketStart).toISOString().slice(0, 10)
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
