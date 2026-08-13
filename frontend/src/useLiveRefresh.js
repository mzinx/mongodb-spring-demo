import { useEffect } from 'react'

/**
 * Calls `onRefresh` whenever the given collection changes, as signalled by the
 * message-queuing live-data service. Two signals count as a change for
 * `collection`:
 *   - a `/sync` event whose `coll` matches (the authoritative per-write push the
 *     live-data service emits for every watched collection), or
 *   - a `/cmd` REFRESH command whose `coll` matches (a coarse hint some views
 *     broadcast after a recompute).
 * Reacting to `/sync` (not just `/cmd`) means a view refreshes off the actual
 * collection write, without relying on a separate REFRESH hint being emitted.
 * `onRefresh` should be a stable reference (useCallback).
 */
export function useLiveRefresh(events, collection, onRefresh) {
  const latest = events[0]
  useEffect(() => {
    if (!latest) return
    const content = latest.payload?.content
    if (content?.coll !== collection) return
    const isSync = latest.channel === '/sync'
    const isCmdRefresh = latest.channel === '/cmd' && content?.type === 'REFRESH'
    if (isSync || isCmdRefresh) onRefresh()
  }, [latest, collection, onRefresh])
}
