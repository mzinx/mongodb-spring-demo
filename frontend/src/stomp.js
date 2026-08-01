import { Client } from '@stomp/stompjs'

// Single STOMP client over the backend's plain WebSocket endpoint (/ws),
// provided by the mongodb-spring-message-queuing module.
// In dev, Vite proxies /ws to the Spring Boot backend.
//
// Heartbeats are set to match the server (mongodb-spring-message-queuing enables
// STOMP heartbeats by default at 10s/10s, via messaging.heartbeat.*) so the
// backend can detect a dead connection — e.g. an intermittent network drop
// with no clean close — within ~one interval and fire SessionDisconnectEvent,
// which drives prompt presence drop-off. These are also stompjs' defaults, but
// pinned here to keep the two ends explicitly aligned.
export const stompClient = new Client({
  brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  reconnectDelay: 3000,
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
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
