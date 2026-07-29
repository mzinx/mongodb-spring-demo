import { Client } from '@stomp/stompjs'

// Single STOMP client over the backend's plain WebSocket endpoint (/ws),
// provided by the mongodb-spring-message-queuing module.
// In dev, Vite proxies /ws to the Spring Boot backend.
export const stompClient = new Client({
  brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  reconnectDelay: 3000,
})

/** Publishes a message to the message-queuing push endpoint. */
export function pushMessage(message) {
  stompClient.publish({ destination: '/push', body: JSON.stringify(message) })
}
