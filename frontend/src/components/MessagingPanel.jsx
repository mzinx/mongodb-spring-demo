import { useState } from 'react'
import { pushMessage } from '../stomp.js'

/**
 * mongodb-spring-message-queuing demo: publishes a message to the /push
 * STOMP endpoint. The backend persists it to the `_messages` collection and a
 * change stream on that collection fans it out to subscribers of the target
 * destination — so the ACK and the RES appearing under Live Events actually
 * travelled through MongoDB.
 */
export default function MessagingPanel({ events }) {
  const [target, setTarget] = useState('/cmd')
  const [content, setContent] = useState('{\n  "type": "NOTIFICATION",\n  "message": "Hello from the demo UI"\n}')
  const [error, setError] = useState(null)
  const [sentAt, setSentAt] = useState(null)

  const send = () => {
    setError(null)
    try {
      pushMessage({ type: 'REQ', target, content: JSON.parse(content) })
      setSentAt(new Date())
    } catch (err) {
      setError(`Invalid content JSON: ${err.message}`)
    }
  }

  const related = events.filter((e) => e.channel === '/cmd').slice(0, 10)

  return (
    <div className="panel split">
      <div className="card">
        <h3>Send message (REQ → /push)</h3>
        <p className="hint">
          Flow: UI → STOMP <code>/push</code> → persisted in <code>_messages</code> (TTL-indexed) → change
          stream (<code>message-service</code>) → broadcast to subscribers of the target destination.
        </p>
        <label>
          Target destination
          <select value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="/cmd">/cmd</option>
            <option value="/sync">/sync</option>
          </select>
        </label>
        <label>
          Content (JSON)
          <textarea rows={6} value={content} onChange={(e) => setContent(e.target.value)} spellCheck={false} />
        </label>
        {error && <p className="error">{error}</p>}
        <div className="actions">
          <button className="primary" onClick={send}>
            Send
          </button>
          {sentAt && <span className="hint">sent at {sentAt.toLocaleTimeString()}</span>}
        </div>
      </div>

      <div className="card">
        <h3>Recent /cmd messages</h3>
        <p className="hint">Expect a message delivered through the MongoDB queue.</p>
        <div className="feed">
          {related.length === 0 && <p className="empty">Nothing received yet.</p>}
          {related.map((e) => (
            <details key={e.id} className="event channel-cmd">
              <summary>
                <span className="tag">{e.payload?.type ?? 'message'}</span>
                <span className="event-time">{e.at.toLocaleTimeString()}</span>
              </summary>
              <pre>{JSON.stringify(e.payload, null, 2)}</pre>
            </details>
          ))}
        </div>
      </div>
    </div>
  )
}
