import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * Test data generator producing change stream events on the `orders`
 * collection straight from the browser.
 */
export default function DataPanel() {
  const [orders, setOrders] = useState([])
  const [busy, setBusy] = useState(false)
  const [lastAction, setLastAction] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(() => {
    api.get('/api/data/orders?limit=20').then(setOrders).catch((err) => setError(err.message))
  }, [])

  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [load])

  const run = async (label, fn) => {
    setBusy(true)
    setError(null)
    try {
      const result = await fn()
      setLastAction(`${label}: ${JSON.stringify(result)}`)
      load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Data generator — `orders` collection</h2>
          <p className="hint">
            Each action triggers change stream events: watch them under <strong>Live Events</strong>{' '}
            (via the seeded <code>orders-demo</code> stream and the message-queuing live-data stream).
          </p>
        </div>
      </div>

      <div className="row-actions toolbar">
        <button className="primary" disabled={busy} onClick={() => run('insert', () => api.post('/api/data/orders/insert?count=1'))}>
          Insert 1 order
        </button>
        <button disabled={busy} onClick={() => run('insert', () => api.post('/api/data/orders/insert?count=10'))}>
          Insert 10 orders
        </button>
        <button disabled={busy} onClick={() => run('update', () => api.post('/api/data/orders/update-random'))}>
          Update random order
        </button>
        <button className="danger" disabled={busy} onClick={() => run('delete', () => api.post('/api/data/orders/delete-random'))}>
          Delete random order
        </button>
      </div>

      {lastAction && <p className="hint mono">{lastAction}</p>}
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
          {orders.map((o) => (
            <tr key={o._id}>
              <td className="mono">{String(o._id).slice(-8)}</td>
              <td>{o.customer}</td>
              <td>{o.product}</td>
              <td>
                <span className={`tag status-${o.status}`}>{o.status}</span>
              </td>
              <td>{o.quantity}</td>
              <td>{typeof o.amount === 'number' ? o.amount.toFixed(2) : o.amount}</td>
              <td>{o.createdAt ? new Date(o.createdAt).toLocaleTimeString() : ''}</td>
            </tr>
          ))}
          {orders.length === 0 && (
            <tr>
              <td colSpan={7} className="empty">
                No orders yet — insert some above.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
