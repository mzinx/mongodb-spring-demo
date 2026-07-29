import { useState } from 'react'

const CHANNEL_LABELS = {
  '/sync': 'live data (message-queuing)',
  '/cmd': 'command (message-queuing)',
}

/**
 * Live WebSocket feed. Events arrive over the STOMP endpoint provided by
 * mongodb-spring-message-queuing:
 *  - /sync : changed documents from collections in `messaging.watch-collections`
 *  - /cmd  : REFRESH commands and ACK/RES messages
 */
export default function LiveEventsPanel({ events, onClear }) {
  const [filter, setFilter] = useState('all')

  // /sync and /cmd always exist; private channels appear once announced.
  const channels = Array.from(new Set(['/sync', '/cmd', ...events.map((e) => e.channel)]))
  const visible = filter === 'all' ? events : events.filter((e) => e.channel === filter)

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Live events</h2>
          <p className="hint">
            Raw feed of the message-queuing WebSocket destinations. Insert/update/delete orders (see{' '}
            <strong>Orders</strong>) and watch the live-data service push the changed documents
            (<code>/sync</code>) and refresh commands (<code>/cmd</code>) in real time.
          </p>
        </div>
        <div className="row-actions">
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="all">all channels</option>
            {channels.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <button onClick={onClear}>Clear</button>
        </div>
      </div>

      <div className="feed">
        {visible.length === 0 && <p className="empty">No events yet.</p>}
        {visible.map((e) => (
          <details key={e.id} className={`event channel-${e.channel.slice(1).replace(/\//g, '-')}`} open={false}>
            <summary>
              <span className="tag">{e.channel}</span>
              <span className="event-title">{summarize(e)}</span>
              <span className="event-time">{e.at.toLocaleTimeString()}</span>
            </summary>
            <pre>{JSON.stringify(e.payload, null, 2)}</pre>
          </details>
        ))}
      </div>
    </div>
  )
}

function summarize(e) {
  const p = e.payload
  if (p && typeof p === 'object') {
    if (p.content?.type === 'REFRESH') return `REFRESH ${p.content.coll}`
    if (p.content?.type === 'LISTEN') return `LISTEN ${p.content.target} (join private channel)`
    if (p.type) return `${p.type} → ${p.target ?? ''}`
  }
  return CHANNEL_LABELS[e.channel] ?? 'message'
}
