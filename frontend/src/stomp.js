import { Client } from '@stomp/stompjs'

// Single STOMP client over the backend's plain WebSocket endpoint (/ws),
// provided by the mongodb-spring-message-queuing module.
// In dev, Vite proxies /ws to the Spring Boot backend.
//
// Heartbeats are set to match the server (mongodb-spring-message-queuing, via
// messaging.heartbeat.*) so the backend can detect a dead connection — e.g. an
// intermittent network drop with no clean close — and fire SessionDisconnectEvent,
// which drives presence drop-off. They MUST stay aligned with the two ends.
//
// The interval is deliberately relaxed to 25s (was 10s). A short heartbeat window
// makes the connection fragile: any late beat — network jitter, a GC/main-thread
// pause, or a background tab throttled by the browser — is read as a dead peer and
// the socket is torn down, forcing a reconnect that drops any /sync and /cmd events
// broadcast during the gap (STOMP delivery is fire-and-forget, no replay). A wider
// window tolerates that jitter and cuts the false-positive disconnect rate; prompt
// presence drop-off is still guaranteed within ~one interval for real drops.
export const stompClient = new Client({
  brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  // Reconnect quickly after a genuine drop to minimise the window of missed events.
  reconnectDelay: 2000,
  heartbeatIncoming: 25000,
  heartbeatOutgoing: 25000,
  // On a heartbeat/comm failure, discard the underlying WebSocket immediately
  // rather than attempting to reuse a socket that has already begun closing.
  // Without this, stompjs' heartbeat timer can flush a frame onto a half-closed
  // socket, which the browser reports as
  // "WebSocket is already in CLOSING or CLOSED state."
  discardWebsocketOnCommFailure: true,
})

/** Publishes a message to the message-queuing push endpoint. */
export function pushMessage(message) {
  stompClient.publish({ destination: '/push', body: JSON.stringify(message) })
}

/**
 * Sends a private message to another online session's channel
 * (`/private/<sessionId>`), routed through the MongoDB message queue. The
 * payload carries the sender's identity so the receiver can render it.
 */
export function sendPrivate(channel, from, text) {
  pushMessage({
    target: channel,
    content: { type: 'PRIVATE', from, text, at: new Date().toISOString() },
  })
}
