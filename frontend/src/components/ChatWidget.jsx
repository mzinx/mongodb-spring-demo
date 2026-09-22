import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

/**
 * Overlay RAG chatbot.
 *
 * A floating button toggles a chat window that sits above the whole app. Each
 * question is answered by the backend RAG pipeline: it runs an Atlas Vector
 * Search (automatic embedding) over the `knowledgeBase` collection to find
 * relevant context, asks the selected provider+model to answer grounded in that
 * context, and appends the turn to this session's `chatMemory` (conversation
 * memory, keyed by the browser's Spring Session id — so history survives
 * reloads).
 *
 * Providers and their models are discovered from GET /api/chat/providers, which
 * only returns usable providers (unavailable ones — e.g. Anthropic with no key —
 * are omitted). The UI shows a provider dropdown and a dependent model dropdown.
 */
export default function ChatWidget() {
  const [open, setOpen] = useState(false)
  const [turns, setTurns] = useState([]) // { question, answer, sources, provider, model, at }
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [providers, setProviders] = useState([]) // { id, label, models[], available }
  const [provider, setProvider] = useState('') // selected provider id
  const [model, setModel] = useState('') // selected model id
  const feedRef = useRef(null)

  // Load persisted conversation memory when the widget first opens.
  const loadHistory = useCallback(async () => {
    try {
      const history = await api.get('/api/chat/history')
      setTurns(
        (history || []).map((t) => ({
          question: t.question,
          answer: t.answer,
          sources: t.sources || [],
          provider: t.provider,
          model: t.model,
          at: t.createdAt ? new Date(t.createdAt) : new Date(),
        })),
      )
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [])

  // Discover available providers + their models and the default provider.
  const loadProviders = useCallback(async () => {
    try {
      const res = await api.get('/api/chat/providers')
      const list = res?.providers || []
      setProviders(list)
      setProvider((prev) => {
        // Keep the current selection if still available; else default/first.
        if (prev && list.some((p) => p.id === prev)) return prev
        return res?.default || list[0]?.id || ''
      })
    } catch (e) {
      setError(e.message)
    }
  }, [])

  useEffect(() => {
    if (open) {
      loadHistory()
      loadProviders()
    }
  }, [open, loadHistory, loadProviders])

  const selected = providers.find((p) => p.id === provider) || null

  // When the provider changes (or providers load), reset the model selection to
  // the provider's first model unless the current model is still valid.
  useEffect(() => {
    const models = selected?.models || []
    setModel((prev) => (prev && models.includes(prev) ? prev : models[0] || ''))
  }, [selected])

  // Keep the feed scrolled to the newest message.
  useEffect(() => {
    if (feedRef.current) feedRef.current.scrollTop = feedRef.current.scrollHeight
  }, [turns, busy])

  const send = useCallback(async () => {
    const question = text.trim()
    if (!question || busy) return
    setError(null)
    setText('')
    // Optimistically show the question with a pending answer.
    const pending = { question, answer: null, sources: [], provider, model, at: new Date() }
    setTurns((prev) => [...prev, pending])
    setBusy(true)
    try {
      const res = await api.post('/api/chat/ask', { question, provider, model })
      setTurns((prev) => {
        const next = prev.slice()
        next[next.length - 1] = {
          question,
          answer: res.answer,
          sources: res.sources || [],
          provider: res.provider,
          model: res.model,
          at: new Date(),
        }
        return next
      })
    } catch (e) {
      setError(e.message)
      setTurns((prev) => {
        const next = prev.slice()
        next[next.length - 1] = { ...next[next.length - 1], answer: `⚠️ ${e.message}` }
        return next
      })
    } finally {
      setBusy(false)
    }
  }, [text, busy, provider, model])

  const clear = useCallback(async () => {
    try {
      await api.del('/api/chat/history')
      setTurns([])
    } catch (e) {
      setError(e.message)
    }
  }, [])

  return (
    <>
      <button
        type="button"
        className={`chat-fab ${open ? 'open' : ''}`}
        onClick={() => setOpen((o) => !o)}
        title={open ? 'Close assistant' : 'Ask the knowledge base'}
        aria-label="Toggle chat assistant"
      >
        {open ? '×' : '💬'}
      </button>

      {open && (
        <div className="chat-overlay" role="dialog" aria-label="Knowledge assistant">
          <div className="chat-head">
            <div>
              <strong>Knowledge Assistant</strong>
              <span className="hint" style={{ display: 'block' }}>
                RAG over Atlas Vector Search
              </span>
            </div>
            <div className="row-actions">
              <button className="link" onClick={clear} title="Clear conversation memory">
                clear
              </button>
              <button className="link" onClick={() => setOpen(false)}>
                close
              </button>
            </div>
          </div>

          <div className="chat-provider">
            {providers.length === 0 ? (
              <span className="hint">
                No chat providers available. Configure one in <code>rag.providers</code> (e.g. start Ollama, or
                set an API key).
              </span>
            ) : (
              <>
                <label className="chat-provider-label">
                  Provider
                  <select
                    value={provider}
                    onChange={(e) => setProvider(e.target.value)}
                    title="Choose which provider answers your questions"
                  >
                    {providers.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.label || p.id}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="chat-provider-label">
                  Model
                  <select
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    title="Choose which model to use"
                    disabled={!selected || (selected.models || []).length === 0}
                  >
                    {(selected?.models || []).map((m) => (
                      <option key={m} value={m}>
                        {m}
                      </option>
                    ))}
                  </select>
                </label>
              </>
            )}
          </div>

          <div className="chat-feed" ref={feedRef}>
            {turns.length === 0 && (
              <p className="empty">
                Ask a question about your uploaded documents. Answers are grounded in the knowledge base.
              </p>
            )}
            {turns.map((t, i) => (
              <div key={i} className="chat-turn">
                <div className="dm dm-out">
                  <span className="dm-text">{t.question}</span>
                </div>
                <div className="dm dm-in">
                  {t.answer == null ? (
                    <span className="dm-text typing">thinking…</span>
                  ) : (
                    <span className="dm-text">{t.answer}</span>
                  )}
                  {(t.sources?.length > 0 || t.model) && (
                    <div className="chat-sources">
                      {dedupeSources(t.sources || []).map((s, j) => (
                        <span key={j} className="tag" title={`score ${Number(s.score).toFixed(3)}`}>
                          {s.fileName}
                        </span>
                      ))}
                      {t.model && (
                        <span className="tag tag-model" title={t.provider}>
                          {t.provider ? `${t.provider} · ` : ''}
                          {t.model}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {error && <p className="error" style={{ margin: '0 12px 8px' }}>{error}</p>}

          <div className="chat-input-row">
            <input
              className="dm-input"
              value={text}
              placeholder="Ask about your documents…"
              disabled={busy}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
            />
            <button className="primary" onClick={send} disabled={busy || !text.trim()}>
              {busy ? '…' : 'Send'}
            </button>
          </div>
        </div>
      )}
    </>
  )
}

/** Collapse repeated chunks of the same file into one source chip. */
function dedupeSources(sources) {
  const seen = new Map()
  for (const s of sources) {
    const prev = seen.get(s.fileName)
    if (!prev || (s.score ?? 0) > (prev.score ?? 0)) seen.set(s.fileName, s)
  }
  return Array.from(seen.values())
}
