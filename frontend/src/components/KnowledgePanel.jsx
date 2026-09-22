import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

/**
 * Knowledge Base manager.
 *
 * Upload PDF / Word documents into the `knowledgeBase` collection. The backend
 * extracts their text (Apache Tika), chunks it, and stores one document per
 * chunk. MongoDB Atlas Vector Search *automatic embedding* (Voyage AI) then
 * indexes each chunk server-side — the app never calls an embedding API. Those
 * chunks are what the overlay chatbot retrieves as context.
 */
export default function KnowledgePanel() {
  const [docs, setDocs] = useState([])
  const [stats, setStats] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const [flash, setFlash] = useState(null)
  const fileInput = useRef(null)

  const refresh = useCallback(async () => {
    try {
      const [list, s] = await Promise.all([
        api.get('/api/knowledge/documents'),
        api.get('/api/knowledge/stats'),
      ])
      setDocs(list || [])
      setStats(s || null)
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const upload = useCallback(
    async (fileList) => {
      const files = Array.from(fileList || [])
      if (files.length === 0) return
      setBusy(true)
      setError(null)
      setFlash(null)
      try {
        const form = new FormData()
        files.forEach((f) => form.append('files', f))
        // Multipart: bypass the JSON api helper and fetch directly, keeping the
        // session cookie so memory/identity line up with the chat.
        const res = await fetch('/api/knowledge/upload', {
          method: 'POST',
          credentials: 'include',
          body: form,
        })
        const body = await res.json().catch(() => ({}))
        if (!res.ok && res.status !== 207) {
          throw new Error(body?.message || `Upload failed (${res.status})`)
        }
        const ok = (body.ingested || []).length
        const failed = (body.failed || []).length
        const chunks = (body.ingested || []).reduce((a, r) => a + (r.chunks || 0), 0)
        setFlash(
          `Ingested ${ok} document${ok === 1 ? '' : 's'} (${chunks} chunk${chunks === 1 ? '' : 's'})` +
            (failed ? ` · ${failed} failed` : ''),
        )
        if (failed && body.failed[0]?.error) setError(body.failed.map((f) => `${f.fileName}: ${f.error}`).join('; '))
        await refresh()
      } catch (e) {
        setError(e.message)
      } finally {
        setBusy(false)
        if (fileInput.current) fileInput.current.value = ''
      }
    },
    [refresh],
  )

  const onDrop = useCallback(
    (e) => {
      e.preventDefault()
      setDragOver(false)
      upload(e.dataTransfer.files)
    },
    [upload],
  )

  const remove = useCallback(
    async (docId) => {
      try {
        await api.del(`/api/knowledge/documents/${docId}`)
        await refresh()
      } catch (e) {
        setError(e.message)
      }
    },
    [refresh],
  )

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Knowledge Base</h2>
          <p className="hint">
            Upload <strong>PDF</strong> or <strong>Word</strong> documents. Text is extracted, chunked, and stored in{' '}
            <code>knowledgeBase</code>, where <strong>Atlas Vector Search automatic embedding</strong> indexes it for
            the chatbot to retrieve.
          </p>
        </div>
        {stats && (
          <div className="cards" style={{ marginBottom: 0 }}>
            <div className="card metric">
              <span className="metric-value">{stats.documents}</span>
              <span className="metric-label">documents</span>
            </div>
            <div className="card metric">
              <span className="metric-value">{stats.chunks}</span>
              <span className="metric-label">chunks</span>
            </div>
          </div>
        )}
      </div>

      <div
        className={`dropzone ${dragOver ? 'drag' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        onClick={() => fileInput.current?.click()}
      >
        <input
          ref={fileInput}
          type="file"
          multiple
          accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          style={{ display: 'none' }}
          onChange={(e) => upload(e.target.files)}
        />
        <div className="dropzone-inner">
          <span className="dropzone-icon">⇪</span>
          <p>{busy ? 'Uploading & indexing…' : 'Drop PDF / Word files here, or click to browse'}</p>
          <span className="hint">Multiple files supported · max 25&nbsp;MB each</span>
        </div>
      </div>

      {flash && <p className="pill ok" style={{ marginTop: 12 }}>{flash}</p>}
      {error && <p className="error" style={{ marginTop: 12 }}>{error}</p>}

      <table className="table" style={{ marginTop: 16 }}>
        <thead>
          <tr>
            <th>Document</th>
            <th>Type</th>
            <th>Chunks</th>
            <th>Uploaded</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.docId}>
              <td>{d.fileName}</td>
              <td className="hint">{shortType(d.contentType)}</td>
              <td>{d.chunks}</td>
              <td className="hint">{d.uploadedAt ? new Date(d.uploadedAt).toLocaleString() : ''}</td>
              <td>
                <button className="danger" onClick={() => remove(d.docId)}>
                  delete
                </button>
              </td>
            </tr>
          ))}
          {docs.length === 0 && (
            <tr>
              <td colSpan={5} className="empty">
                No documents yet — upload a PDF or Word file to build the knowledge base.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function shortType(ct) {
  if (!ct) return ''
  if (ct.includes('pdf')) return 'PDF'
  if (ct.includes('word') || ct.includes('msword')) return 'Word'
  return ct
}
