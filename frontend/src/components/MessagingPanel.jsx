import { useEffect, useMemo, useState } from 'react'
import { sendPrivate } from '../stomp.js'

/**
 * Private messaging built on Spring Session presence.
 *
 * Identity comes from Spring Session (persisted in MongoDB): every browser has a
 * stable session id and an inbox channel `/private/<sessionId>` it subscribes to
 * on connect. The backend tracks WebSocket connect/disconnect and broadcasts the
 * live roster of active sessions on `/cmd` (type PRESENCE), which the UI renders
 * below as "who's online".
 *
 * To start a private chat you pick an online session and send to its
 * `/private/<sessionId>` channel. The message travels through the MongoDB
 * message queue (persisted in `_messages`, fanned out by a change stream) and is
 * delivered only to that session's subscriber. Because the target is the
 * receiver's own session channel, only that specific person receives it.
 */
export default function MessagingPanel({ events, me, activeSessions = [], addEvent }) {
  const [peer, setPeer] = useState(null)
  const [text, setText] = useState('')
  const [error, setError] = useState(null)
  // Per-sender "last read" timestamps (keyed by the sender's display name, which
  // is how incoming PRIVATE messages identify who they're from). Anything newer
  // than this counts as unread and drives the bubble on the session list.
  const [lastRead, setLastRead] = useState({})

  // Other people online (exclude ourselves).
  const others = useMemo(
    () => activeSessions.filter((s) => s.sessionId !== me?.sessionId),
    [activeSessions, me],
  )

  // Unread count per sender display name: PRIVATE messages that arrived on our
  // own inbox channel, newer than when we last read that sender's thread. The
  // currently-open peer is always treated as read (its thread is on screen), so
  // it never shows a bubble and there's no flash before the "mark read" effect.
  const unreadByName = useMemo(() => {
    if (!me) return {}
    const counts = {}
    for (const e of events) {
      const c = e.payload?.content
      if (c?.type !== 'PRIVATE') continue
      if (e.channel !== me.channel) continue // only messages received by us
      const from = c.from
      if (!from || from === me.displayName) continue
      if (from === peer?.displayName) continue // open thread = read
      const seenAt = lastRead[from] || 0
      if (e.at.getTime() > seenAt) counts[from] = (counts[from] || 0) + 1
    }
    return counts
  }, [events, me, peer, lastRead])

  // Keep the open peer's "last read" marker advancing as new messages arrive, so
  // that after switching away the count only reflects messages received later.
  useEffect(() => {
    if (!peer?.displayName) return
    setLastRead((prev) => ({ ...prev, [peer.displayName]: Date.now() }))
  }, [peer, events])

  const openPeer = (s) => {
    setPeer(s)
    if (s?.displayName) {
      setLastRead((prev) => ({ ...prev, [s.displayName]: Date.now() }))
    }
  }

  // Conversation with the selected peer: private messages we received on our own
  // inbox from them, plus ones we sent to their channel.
  const conversation = useMemo(() => {
    if (!peer || !me) return []
    return events
      .filter((e) => {
        const c = e.payload?.content
        if (c?.type !== 'PRIVATE') return false
        // Received: arrived on our inbox channel, sent by the peer.
        const received = e.channel === me.channel && c.from === peer.displayName
        // Sent: we published to the peer's channel.
        const sent = e.channel === peer.channel
        return received || sent
      })
      .slice()
      .reverse()
  }, [events, peer, me])

  const send = () => {
    setError(null)
    if (!peer) {
      setError('Select someone online first.')
      return
    }
    const body = text.trim()
    if (!body) return
    const from = me?.displayName || 'anonymous'
    sendPrivate(peer.channel, from, body)
    // Echo our own outgoing message locally: it is delivered to the peer's
    // inbox (which we don't subscribe to), so without this the sender would
    // never see their own messages in the conversation.
    addEvent?.(
      peer.channel,
      JSON.stringify({ target: peer.channel, content: { type: 'PRIVATE', from, text: body, at: new Date().toISOString() } }),
    )
    setText('')
  }

  return (
    <div className="panel split">
      <div className="card">
        <h3>Active sessions</h3>
        <p className="hint">
          Presence from <strong>Spring Session (MongoDB)</strong>: each browser keeps a session in the{' '}
          <code>sessions</code> collection, and the backend broadcasts a <code>PRESENCE</code> roster on{' '}
          <code>/cmd</code> as sessions connect/disconnect. Open a second browser (or private window) to see
          another session appear here.
        </p>
        <ul className="session-list">
          {others.length === 0 && <li className="empty">No one else is online right now.</li>}
          {others.map((s) => {
            const unread = unreadByName[s.displayName] || 0
            return (
              <li
                key={s.sessionId}
                className={`session-item ${peer?.sessionId === s.sessionId ? 'active' : ''}`}
                onClick={() => openPeer(s)}
              >
                <span className="dot ok" />
                <span className="session-name">{s.displayName || 'anonymous'}</span>
                {unread > 0 && (
                  <span className="unread-bubble" title={`${unread} new message${unread > 1 ? 's' : ''}`}>
                    {unread > 99 ? '99+' : unread}
                  </span>
                )}
                <span className="session-id mono">{s.sessionId.slice(0, 8)}…</span>
                <button
                  className="link"
                  onClick={(ev) => {
                    ev.stopPropagation()
                    openPeer(s)
                  }}
                >
                  open private channel
                </button>
              </li>
            )
          })}
        </ul>
        {me && (
          <p className="hint mono">
            you: {me.displayName} · inbox {me.channel}
          </p>
        )}
      </div>

      <div className="card">
        <h3>
          Private chat
          {peer && <span className="tag" style={{ marginLeft: 8 }}>{peer.displayName}</span>}
        </h3>
        {!peer && <p className="hint">Select an active session on the left to open a private channel.</p>}
        {peer && (
          <>
            <p className="hint">
              Messages go to <code>{peer.channel}</code> — routed through MongoDB (<code>_messages</code> →
              change stream) and delivered only to <strong>{peer.displayName}</strong>'s session.
            </p>
            <div className="feed">
              {conversation.length === 0 && <p className="empty">No messages yet. Say hello.</p>}
              {conversation.map((e) => {
                const mine = e.channel === peer.channel
                return (
                  <div key={e.id} className={`dm ${mine ? 'dm-out' : 'dm-in'}`}>
                    <span className="dm-from">{mine ? me?.displayName : e.payload.content.from}</span>
                    <span className="dm-text">{e.payload.content.text}</span>
                    <span className="event-time">{e.at.toLocaleTimeString()}</span>
                  </div>
                )
              })}
            </div>
            {error && <p className="error">{error}</p>}
            <div className="actions">
              <input
                className="dm-input"
                value={text}
                placeholder={`Message ${peer.displayName}…`}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && send()}
              />
              <button className="primary" onClick={send}>
                Send
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
