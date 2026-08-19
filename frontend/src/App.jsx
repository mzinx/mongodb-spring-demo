import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { stompClient } from './stomp.js'
import OrdersPanel from './components/OrdersPanel.jsx'
import DashboardPanel from './components/DashboardPanel.jsx'
import LiveEventsPanel from './components/LiveEventsPanel.jsx'
import MessagingPanel from './components/MessagingPanel.jsx'

const TABS = ['Dashboard', 'Orders', 'Messaging']
const CHANNELS = ['/sync', '/cmd']
const MAX_EVENTS = 300

// Module-level guard so the identity bootstrap runs exactly once, even under
// React 18 StrictMode (which intentionally mounts/effects twice in dev) and
// across Fast-Refresh re-renders.
let bootStarted = false

export default function App() {
  const [tab, setTab] = useState('Dashboard')
  const [connected, setConnected] = useState(false)
  // User-controlled intent to be offline. When true, the STOMP client is
  // deactivated so it stops auto-reconnecting; the pill/toggle reflect a
  // deliberate offline state rather than an involuntary drop.
  const [offline, setOffline] = useState(false)
  const [events, setEvents] = useState([])
  // The current browser's Spring Session identity (persisted in MongoDB).
  const [me, setMe] = useState(null)
  // Live roster of active sessions, driven by /cmd PRESENCE broadcasts.
  const [activeSessions, setActiveSessions] = useState([])
  const meRef = useRef(null)
  const counter = useRef(0)
  // Active STOMP subscriptions, so a reconnect can drop the previous ones before
  // re-subscribing — otherwise onConnect (which fires on every reconnect) would
  // accumulate duplicate subscriptions and every broadcast would be counted once
  // per accumulated subscription.
  const subscriptions = useRef([])

  const addEvent = useCallback((channel, body) => {
    let payload
    try {
      payload = JSON.parse(body)
    } catch {
      payload = body
    }
    setEvents((prev) =>
      [{ id: ++counter.current, channel, at: new Date(), payload }, ...prev].slice(0, MAX_EVENTS),
    )
    return payload
  }, [])

  const handleMessage = useCallback(
    (channel, body) => {
      const payload = addEvent(channel, body)
      // Presence roster is pushed by the backend whenever a session connects
      // or disconnects (keyed by the browser's Spring Session id).
      if (channel === '/cmd' && payload?.content?.type === 'PRESENCE') {
        setActiveSessions(Array.isArray(payload.content.sessions) ? payload.content.sessions : [])
      }
    },
    [addEvent],
  )

  // Establish the Spring Session identity (display name) BEFORE opening the
  // WebSocket, so the handshake carries the chosen nickname into the presence
  // roster. Then activate STOMP and subscribe to this browser's own private
  // inbox channel plus the shared /sync and /cmd destinations.
  //
  // Runs once for the lifetime of the page (guarded against StrictMode's double
  // effect invocation) so the user is only ever prompted a single time; the
  // MongoDB-backed Spring Session is reused across reloads.
  useEffect(() => {
    if (bootStarted) return
    bootStarted = true

    async function boot() {
      // Ensure a session cookie exists and learn our id / channel / stored name.
      let session = await api.get('/api/session/me').catch(() => null)

      // Only prompt if we don't already have a name for this session. The name
      // is persisted on the Spring Session (server) and mirrored in
      // localStorage, so a reload reuses it without asking again.
      let name = (session?.displayName || localStorage.getItem('displayName') || '').trim()
      if (!name) {
        name = (window.prompt('Choose a display name for messaging', '') || 'anonymous').trim() || 'anonymous'
      }
      localStorage.setItem('displayName', name)

      // Persist the name on the session (also refreshes it if it changed).
      session = await api.post('/api/session/name', { displayName: name }).catch(() => session)
      if (!session) return
      meRef.current = session
      setMe(session)

      // Refreshes the roster from the authoritative DB-backed endpoint.
      const refreshRoster = () =>
        api.get('/api/session/active').then((list) => setActiveSessions(list || [])).catch(() => {})

      // Seed the roster with whoever is already online.
      refreshRoster()

      stompClient.onConnect = () => {
        setConnected(true)
        // onConnect fires on every (re)connect. Drop any subscriptions from a
        // previous connection first so they don't accumulate — otherwise each
        // broadcast would be delivered once per stale subscription (the "6×
        // REFRESH" symptom).
        subscriptions.current.forEach((sub) => {
          try {
            sub.unsubscribe()
          } catch {
            /* subscription from a dead connection — ignore */
          }
        })
        subscriptions.current = []

        CHANNELS.forEach((dest) =>
          subscriptions.current.push(stompClient.subscribe(dest, (msg) => handleMessage(dest, msg.body))),
        )
        // Subscribe to our own private inbox so we receive DMs addressed to us.
        if (meRef.current?.channel) {
          subscriptions.current.push(
            stompClient.subscribe(meRef.current.channel, (msg) => addEvent(meRef.current.channel, msg.body)),
          )
        }
        // Re-sync the authoritative roster now that we're (re)connected. This
        // fires on every (re)connect, so it also heals the roster after a drop:
        // any PRESENCE snapshot broadcast on /cmd while we were offline is lost
        // (STOMP is fire-and-forget, no replay), which would otherwise leave us
        // showing a stale list until the 15s poll. Fetching immediately pulls the
        // authoritative snapshot right away instead of waiting for that poll.
        //
        // A second, short-delayed fetch additionally covers the connect-time
        // PRESENCE race: the server's connect broadcast can be emitted just before
        // our /cmd subscription above is registered, so the immediate fetch might
        // still predate a peer's very-recent change; the follow-up reconciles it.
        refreshRoster()
        setTimeout(refreshRoster, 300)
      }
      stompClient.onWebSocketClose = () => setConnected(false)
      stompClient.activate()
    }

    boot()
    // Intentionally no cleanup that deactivates the client: StrictMode's
    // immediate unmount/remount in dev would otherwise tear down the socket we
    // just started. The connection lives for the page lifetime.
  }, [addEvent, handleMessage])

  // Poll the presence roster as a backstop to the /cmd PRESENCE broadcasts.
  // Broadcasts fire on connect/disconnect, but a session that was grace-expired
  // on disconnect only *leaves* the roster when its shortened TTL lapses a little
  // later — with no broadcast at that moment. This cheap, DB-authoritative poll
  // ensures departed sessions drop off every client within the interval even on
  // an otherwise-quiet system. It also keeps our own keep-alive fresh.
  useEffect(() => {
    let alive = true
    const load = () =>
      api.get('/api/session/active').then((list) => alive && setActiveSessions(list || [])).catch(() => {})
    const timer = setInterval(load, 15000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])

  // Tracks the user's offline intent for the toggle handler without reading it
  // from a setState updater — updaters must stay pure (React 18 StrictMode runs
  // them twice, which would fire the socket teardown/reopen twice and race the
  // close). `transitioning` serialises transitions so a rapid toggle can't call
  // activate() while a previous deactivate() is still tearing the socket down —
  // that race is what surfaces the browser's "WebSocket is already in CLOSING or
  // CLOSED state" warning (a pending heartbeat/frame gets flushed onto a socket
  // that has already begun closing).
  const offlineRef = useRef(false)
  const transitioning = useRef(false)

  // User toggle between online and offline. Going offline deactivates the STOMP
  // client (which also stops its auto-reconnect loop); going back online
  // re-activates it, re-runs onConnect and re-subscribes to all channels. The
  // activate()/deactivate() side effects run here (not in a setState updater) and
  // are serialised via `transitioning`.
  const toggleConnection = useCallback(async () => {
    if (transitioning.current) return
    transitioning.current = true

    const goingOffline = !offlineRef.current
    offlineRef.current = goingOffline
    setOffline(goingOffline)

    try {
      if (goingOffline) {
        setConnected(false)
        // deactivate() resolves once the socket has fully closed; awaiting it
        // guarantees a subsequent "go online" can't reopen mid-close.
        await stompClient.deactivate()
      } else {
        stompClient.activate()
      }
    } finally {
      transitioning.current = false
    }
  }, [])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-logo">⧉</span>
          <div>
            <h1>mongodb-spring demo</h1>
            <span className="subtitle">discovery · sessions · messaging · live data</span>
          </div>
        </div>
        <div className="topbar-right">
          {me && (
            <span className="pill" title={`Spring Session ${me.sessionId}`}>
              you: {me.displayName || 'anonymous'}
            </span>
          )}
          <span className={`pill ${connected ? 'ok' : 'bad'}`}>
            <span className="dot" />{' '}
            {connected
              ? 'WebSocket connected'
              : offline
                ? 'WebSocket offline (by you)'
                : 'WebSocket offline'}
          </span>
          <button
            type="button"
            className={`pill pill-toggle ${offline ? 'bad' : 'ok'}`}
            onClick={toggleConnection}
            title={offline ? 'Reconnect the WebSocket' : 'Disconnect the WebSocket'}
          >
            {offline ? 'Go online' : 'Go offline'}
          </button>
        </div>
      </header>

      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t} className={`tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
            {t}
            {t === 'Live Events' && events.length > 0 && <span className="badge">{events.length}</span>}
            {t === 'Messaging' && activeSessions.length > 0 && <span className="badge">{activeSessions.length}</span>}
          </button>
        ))}
      </nav>

      <main className="content">
        {tab === 'Dashboard' && <DashboardPanel events={events} />}
        {tab === 'Orders' && <OrdersPanel events={events} />}
        {tab === 'Messaging' && (
          <MessagingPanel events={events} me={me} activeSessions={activeSessions} addEvent={addEvent} />
        )}
        <LiveEventsPanel events={events} onClear={() => setEvents([])} />
      </main>
    </div>
  )
}
