import { useState } from 'react'
import { pushMessage } from '../stomp.js'

/**
 * mongodb-spring-message-queuing demo: publishes a message to the /push
 * STOMP endpoint. The backend persists it to the `_messages` collection and a
 * change stream on that collection fans it out to subscribers of the target
 * destination — so the Live Events actually travelled through MongoDB.
 *
 * Private channel protocol (demo-level, built on the generic Message.target):
 *   1. a client announces `{type: "LISTEN", target: "/private/<id>"}` on /cmd
 *      (through the queue, so every connected client receives it),
 *   2. each client receiving the LISTEN command subscribes to that target
 *      (see App.jsx), forming a private channel — clients connecting later
 *      never subscribe to it,
 *   3. messages sent to the private target are only delivered to its
 *      subscribers.
 */
export default function MessagingPanel({ events, privateChannels = [] }) {
  const [target, setTarget] = useState('/cmd')
  const [content, setContent] = useState('{\n  "type": "NOTIFICATION",\n  "message": "Hello from the demo UI"\n}')
  const [error, setError] = useState(null)
  const [sentAt, setSentAt] = useState(null)
  const [announced, setAnnounced] = useState(null)

  const send = () => {
    setError(null)
    try {
      pushMessage({ target, content: JSON.parse(content) })
      setSentAt(new Date())
    } catch (err) {
      setError(`Invalid content JSON: ${err.message}`)
    }
  }

  const openPrivateChannel = () => {
    setError(null)
    const dest = `/private/${Math.random().toString(36).slice(2, 8)}`
    // Announced through the queue: every currently connected client receives
    // the LISTEN command on /cmd and subscribes to the new destination.
    pushMessage({ target: '/cmd', content: { type: 'LISTEN', target: dest } })
    setAnnounced(dest)
  }

  const related = events
    .filter((e) => e.channel === '/cmd' || privateChannels.includes(e.channel))
    .slice(0, 10)

  return (
    <div className="panel split">
      <div className="card">
        <h3>Send message (/push)</h3>
        <p className="hint">
          Flow: UI → STOMP <code>/push</code> → persisted in <code>_messages</code> (TTL-indexed) → change
          stream (<code>message-service</code>) → broadcast to subscribers of the target destination.
        </p>
        <label>
          Target destination
          <select value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="/cmd">/cmd</option>
            <option value="/sync">/sync</option>
            {privateChannels.map((c) => (
              <option key={c} value={c}>
                {c} (private)
              </option>
            ))}
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

        <h4>Private channels</h4>
        <p className="hint">
          A <code>LISTEN</code> command tells every <em>currently connected</em> client to subscribe to a
          new destination. Open this page in a second browser tab, create a channel, and send to it from
          either tab — then open a third tab and note it does not receive the channel's messages.
        </p>
        <div className="actions">
          <button onClick={openPrivateChannel}>Create private channel</button>
          {announced && (
            <span className="hint mono">
              announced {announced}
              {privateChannels.includes(announced) ? ' — joined' : ' — waiting for LISTEN…'}
            </span>
          )}
        </div>
        {privateChannels.length > 0 && (
          <p className="hint">
            joined: {privateChannels.map((c) => (
              <span key={c} className="tag" style={{ marginRight: 4 }}>
                {c}
              </span>
            ))}
          </p>
        )}
      </div>

      <div className="card">
        <h3>Recent messages</h3>
        <p className="hint">Messages on /cmd and joined private channels, delivered through the MongoDB queue.</p>
        <div className="feed">
          {related.length === 0 && <p className="empty">Nothing received yet.</p>}
          {related.map((e) => (
            <details key={e.id} className="event channel-cmd">
              <summary>
                <span className="tag">{e.channel}</span>
                <span className="event-title">{e.payload?.content?.type ?? 'message'}</span>
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
