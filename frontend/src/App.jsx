import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { stompClient } from './stomp.js'
import StreamsPanel from './components/StreamsPanel.jsx'
import OrdersPanel from './components/OrdersPanel.jsx'
import DashboardPanel from './components/DashboardPanel.jsx'
import LiveEventsPanel from './components/LiveEventsPanel.jsx'
import AggregationPanel from './components/AggregationPanel.jsx'
import MessagingPanel from './components/MessagingPanel.jsx'

const TABS = ['Streams', 'Orders', 'Dashboard', 'Live Events', 'Aggregations', 'Messaging']
const CHANNELS = ['/sync', '/cmd']
const MAX_EVENTS = 300

export default function App() {
  const [tab, setTab] = useState('Streams')
  const [connected, setConnected] = useState(false)
  const [events, setEvents] = useState([])
  const [instances, setInstances] = useState([])
  const [privateChannels, setPrivateChannels] = useState([])
  const privateChannelsRef = useRef(new Set())
  const counter = useRef(0)

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

  // Private channel protocol: a LISTEN command received on /cmd instructs
  // every connected client to subscribe to the announced destination, forming
  // a private channel (clients connecting later never subscribe to it).
  const subscribePrivate = useCallback(
    (dest) => {
      if (typeof dest !== 'string' || !dest.startsWith('/') || privateChannelsRef.current.has(dest)) return
      privateChannelsRef.current.add(dest)
      setPrivateChannels(Array.from(privateChannelsRef.current))
      if (stompClient.connected) stompClient.subscribe(dest, (msg) => addEvent(dest, msg.body))
    },
    [addEvent],
  )

  const handleMessage = useCallback(
    (channel, body) => {
      const payload = addEvent(channel, body)
      if (channel === '/cmd' && payload?.content?.type === 'LISTEN') subscribePrivate(payload.content.target)
    },
    [addEvent, subscribePrivate],
  )

  // STOMP lifecycle: subscribe to the live data (/sync) and command (/cmd)
  // destinations exposed via mongodb-spring-message-queuing, plus any private
  // channels announced through LISTEN commands (re-subscribed on reconnect).
  useEffect(() => {
    stompClient.onConnect = () => {
      setConnected(true)
      CHANNELS.forEach((dest) => stompClient.subscribe(dest, (msg) => handleMessage(dest, msg.body)))
      privateChannelsRef.current.forEach((dest) =>
        stompClient.subscribe(dest, (msg) => addEvent(dest, msg.body)),
      )
    }
    stompClient.onWebSocketClose = () => setConnected(false)
    stompClient.activate()
    return () => {
      stompClient.deactivate()
      setConnected(false)
    }
  }, [addEvent, handleMessage])

  // Poll the discovery instance registry.
  useEffect(() => {
    let alive = true
    const load = () => api.get('/api/instances').then((data) => alive && setInstances(data)).catch(() => {})
    load()
    const timer = setInterval(load, 10000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-logo">⧉</span>
          <div>
            <h1>mongodb-spring demo</h1>
            <span className="subtitle">change streams · discovery · messaging · aggregation</span>
          </div>
        </div>
        <div className="topbar-right">
          <span className="pill" title="Instances registered via mongodb-spring-discovery heartbeats">
            instances: {instances.length ? instances.join(', ') : '—'}
          </span>
          <span className={`pill ${connected ? 'ok' : 'bad'}`}>
            <span className="dot" /> {connected ? 'WebSocket connected' : 'WebSocket offline'}
          </span>
        </div>
      </header>

      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t} className={`tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
            {t}
            {t === 'Live Events' && events.length > 0 && <span className="badge">{events.length}</span>}
          </button>
        ))}
      </nav>

      <main className="content">
        {tab === 'Streams' && <StreamsPanel />}
        {tab === 'Orders' && <OrdersPanel events={events} />}
        {tab === 'Dashboard' && <DashboardPanel events={events} />}
        {tab === 'Live Events' && <LiveEventsPanel events={events} onClear={() => setEvents([])} />}
        {tab === 'Aggregations' && <AggregationPanel />}
        {tab === 'Messaging' && <MessagingPanel events={events} privateChannels={privateChannels} />}
      </main>
    </div>
  )
}
