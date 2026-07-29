import { useState } from 'react'

const CHANNEL_LABELS = {
  '/events': 'stream event (eventRelay listener)',
  '/sync': 'live data (message-queuing)',
  '/cmd': 'command (message-queuing)',
}

/**
 * Live WebSocket feed. Events arrive over the STOMP endpoint provided by
 * mongodb-spring-message-queuing:
 *  - /events : change stream events relayed by the demo `eventRelay` listener
 *  - /sync   : documents from collections in `messaging.watch-collections`
 *  - /cmd    : command/ACK/RES messages
 */
export default function LiveEventsPanel({ events, onClear }) {
  const [filter, setFilter] = useState('all')

  const visible = filter === 'all' ? events : events.filter((e) => e.channel === filter)

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Live events</h2>
          <p className="hint">
            Insert/update/delete data (see <strong>Data Generator</strong>) while a stream targeting the{' '}
            <code>eventRelay</code> listener is running, and watch events arrive here in real time.
          </p>
        </div>
        <div className="row-actions">
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="all">all channels</option>
            <option value="/events">/events</option>
            <option value="/sync">/sync</option>
            <option value="/cmd">/cmd</option>
          </select>
          <button onClick={onClear}>Clear</button>
        </div>
      </div>

      <div className="feed">
        {visible.length === 0 && <p className="empty">No events yet.</p>}
        {visible.map((e) => (
          <details key={e.id} className={`event channel-${e.channel.slice(1)}`} open={false}>
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
    if (e.channel === '/events')
      return `${p.operationType ?? '?'} on ${p.database ?? ''}.${p.collection ?? '?'}`
    if (p.type) return `${p.type} → ${p.t ?? p.target ?? ''}`
  }
  return CHANNEL_LABELS[e.channel] ?? 'message'
}
