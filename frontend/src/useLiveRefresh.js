import { useEffect } from 'react'

/**
 * Calls `onRefresh` whenever the message-queuing live-data service broadcasts
 * a REFRESH command for the given collection on the /cmd destination
 * (i.e. whenever a change stream event occurred on that collection).
 * `onRefresh` should be a stable reference (useCallback).
 */
export function useLiveRefresh(events, collection, onRefresh) {
  const latest = events[0]
  useEffect(() => {
    if (!latest || latest.channel !== '/cmd') return
    const content = latest.payload?.content
    if (content?.type === 'REFRESH' && content?.coll === collection) onRefresh()
  }, [latest, collection, onRefresh])
}
