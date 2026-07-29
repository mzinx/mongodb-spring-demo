import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useLiveRefresh } from '../useLiveRefresh.js'

const PAGE_SIZES = [5, 10, 20, 50]

/**
 * Paginated orders page. The list is fetched through the aggregation
 * library's pagination support and kept in sync in real time: the
 * message-queuing live-data service watches the `orders` collection and
 * broadcasts a REFRESH command on /cmd for every change, which re-fetches the
 * current page. The generator buttons produce those changes.
 */
export default function OrdersPanel({ events }) {
  const [data, setData] = useState({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [refreshedAt, setRefreshedAt] = useState(null)

  const load = useCallback(
    (silent = false) =>
      api
        .get(`/api/data/orders?page=${page}&size=${size}`)
        .then((d) => {
          setData(d)
          if (silent) setRefreshedAt(new Date())
          setError(null)
        })
        .catch((err) => setError(err.message)),
    [page, size],
  )

  useEffect(() => {
    load()
  }, [load])

  // Real-time: re-fetch the current page whenever the live-data service
  // reports a change on the orders collection.
  const onRefresh = useCallback(() => load(true), [load])
  useLiveRefresh(events, 'orders', onRefresh)

  const run = async (fn) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
      // no manual reload: the live-data REFRESH command triggers it
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const totalPages = Math.max(data.totalPages, 1)

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Orders</h2>
          <p className="hint">
            Live view of the <code>orders</code> collection. Changes are detected by the message-queuing{' '}
            <code>live-data</code> change stream and pushed over WebSocket — the page refreshes itself, try
            the buttons below or write from <code>mongosh</code>/Compass.
          </p>
        </div>
        {refreshedAt && (
          <span className="pill ok" title="Triggered by a REFRESH command on /cmd">
            live-refreshed {refreshedAt.toLocaleTimeString()}
          </span>
        )}
      </div>

      <div className="row-actions toolbar">
        <button className="primary" disabled={busy} onClick={() => run(() => api.post('/api/data/orders/insert?count=1'))}>
          Insert 1 order
        </button>
        <button disabled={busy} onClick={() => run(() => api.post('/api/data/orders/insert?count=10'))}>
          Insert 10 orders
        </button>
        <button disabled={busy} onClick={() => run(() => api.post('/api/data/orders/update-random'))}>
          Update random order
        </button>
        <button className="danger" disabled={busy} onClick={() => run(() => api.post('/api/data/orders/delete-random'))}>
          Delete random order
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      <table className="table">
        <thead>
          <tr>
            <th>Id</th>
            <th>Customer</th>
            <th>Product</th>
            <th>Status</th>
            <th>Qty</th>
            <th>Amount</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {data.content.map((o) => (
            <tr key={o._id}>
              <td className="mono">{String(o._id).slice(-8)}</td>
              <td>{o.customer}</td>
              <td>{o.product}</td>
              <td>
                <span className={`tag status-${o.status}`}>{o.status}</span>
              </td>
              <td>{o.quantity}</td>
              <td>{typeof o.amount === 'number' ? o.amount.toFixed(2) : o.amount}</td>
              <td>{o.createdAt ? new Date(o.createdAt).toLocaleString() : ''}</td>
            </tr>
          ))}
          {data.content.length === 0 && (
            <tr>
              <td colSpan={7} className="empty">
                No orders on this page — insert some above.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="pagination">
        <span className="hint">
          {data.totalElements} order(s) · page {data.page + 1} / {totalPages}
        </span>
        <div className="row-actions">
          <button disabled={page === 0} onClick={() => setPage(0)}>
            «
          </button>
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            ‹ Prev
          </button>
          <button disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)}>
            Next ›
          </button>
          <button disabled={page >= totalPages - 1} onClick={() => setPage(totalPages - 1)}>
            »
          </button>
          <select
            value={size}
            onChange={(e) => {
              setSize(Number(e.target.value))
              setPage(0)
            }}
          >
            {PAGE_SIZES.map((s) => (
              <option key={s} value={s}>
                {s} / page
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  )
}
