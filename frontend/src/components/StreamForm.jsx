import { useState } from 'react'
import { api } from '../api.js'

const MODES = ['BROADCAST', 'AUTO_RECOVER', 'AUTO_SCALE']
const RESUME_STRATEGIES = ['NONE', 'PER_EVENT', 'PER_BATCH', 'INTERVAL']
const FULL_DOCUMENT = ['', 'DEFAULT', 'UPDATE_LOOKUP', 'WHEN_AVAILABLE', 'REQUIRED']
const FULL_DOCUMENT_BEFORE_CHANGE = ['', 'DEFAULT', 'OFF', 'WHEN_AVAILABLE', 'REQUIRED']

/**
 * Create / edit form for a change stream definition
 * (persisted through ChangeStreamConfigService on the backend).
 */
export default function StreamForm({ initial, listeners, onSaved, onCancel }) {
  const editing = Boolean(initial?.id)
  const [form, setForm] = useState({
    id: initial?.id ?? '',
    collectionName: initial?.collectionName ?? '',
    mode: initial?.mode ?? 'BROADCAST',
    resumeStrategy: initial?.resumeStrategy ?? 'NONE',
    checkpointInterval: initial?.checkpointInterval ?? '',
    batchSize: initial?.batchSize ?? '',
    maxAwaitTime: initial?.maxAwaitTime ?? '',
    fullDocument: initial?.fullDocument ?? '',
    fullDocumentBeforeChange: initial?.fullDocumentBeforeChange ?? '',
    listener: initial?.listener ?? (listeners.includes('consoleLog') ? 'consoleLog' : listeners[0] ?? ''),
    enabled: initial?.enabled ?? true,
    pipeline: JSON.stringify(initial?.pipeline ?? [], null, 2),
  })
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  const set = (key) => (e) =>
    setForm((f) => ({ ...f, [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }))

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    let pipeline
    try {
      pipeline = JSON.parse(form.pipeline || '[]')
      if (!Array.isArray(pipeline)) throw new Error('pipeline must be a JSON array of stages')
    } catch (err) {
      setError(`Invalid pipeline JSON: ${err.message}`)
      return
    }
    setSaving(true)
    try {
      await api.post('/api/streams', {
        id: form.id.trim(),
        collectionName: form.collectionName.trim() || null,
        mode: form.mode,
        resumeStrategy: form.resumeStrategy,
        checkpointInterval: form.checkpointInterval === '' ? null : Number(form.checkpointInterval),
        batchSize: form.batchSize === '' ? null : Number(form.batchSize),
        maxAwaitTime: form.maxAwaitTime === '' ? null : Number(form.maxAwaitTime),
        fullDocument: form.fullDocument || null,
        fullDocumentBeforeChange: form.fullDocumentBeforeChange || null,
        listener: form.listener,
        enabled: form.enabled,
        pipeline,
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h3>{editing ? `Configure stream “${initial.id}”` : 'Create change stream'}</h3>

      <div className="form-grid">
        <label>
          Stream id *
          <input value={form.id} onChange={set('id')} required disabled={editing} placeholder="orders-stream" />
        </label>
        <label>
          Collection <small>(empty = whole database)</small>
          <input value={form.collectionName} onChange={set('collectionName')} placeholder="orders" />
        </label>
        <label>
          Mode
          <select value={form.mode} onChange={set('mode')}>
            {MODES.map((m) => (
              <option key={m}>{m}</option>
            ))}
          </select>
        </label>
        <label>
          Listener bean
          <select value={form.listener} onChange={set('listener')}>
            {listeners.map((l) => (
              <option key={l}>{l}</option>
            ))}
          </select>
        </label>
        <label>
          Resume strategy
          <select value={form.resumeStrategy} onChange={set('resumeStrategy')}>
            {RESUME_STRATEGIES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        {form.resumeStrategy === 'INTERVAL' && (
          <label>
            Checkpoint interval (ms)
            <input type="number" value={form.checkpointInterval} onChange={set('checkpointInterval')} placeholder="60000" />
          </label>
        )}
        <label>
          Full document
          <select value={form.fullDocument} onChange={set('fullDocument')}>
            {FULL_DOCUMENT.map((v) => (
              <option key={v} value={v}>
                {v || '(driver default)'}
              </option>
            ))}
          </select>
        </label>
        <label>
          Full document before change
          <select value={form.fullDocumentBeforeChange} onChange={set('fullDocumentBeforeChange')}>
            {FULL_DOCUMENT_BEFORE_CHANGE.map((v) => (
              <option key={v} value={v}>
                {v || '(driver default)'}
              </option>
            ))}
          </select>
        </label>
        <label>
          Batch size
          <input type="number" value={form.batchSize} onChange={set('batchSize')} placeholder="1000" />
        </label>
        <label>
          Max await time (ms)
          <input type="number" value={form.maxAwaitTime} onChange={set('maxAwaitTime')} placeholder="800" />
        </label>
      </div>

      <label className="full-width">
        Aggregation pipeline (JSON array of stages)
        <textarea rows={6} value={form.pipeline} onChange={set('pipeline')} spellCheck={false} />
      </label>

      <label className="checkbox">
        <input type="checkbox" checked={form.enabled} onChange={set('enabled')} />
        Enabled (stream starts on the next reconcile cycle)
      </label>

      {error && <p className="error">{error}</p>}

      <div className="actions">
        <button type="submit" className="primary" disabled={saving}>
          {saving ? 'Saving…' : editing ? 'Save changes' : 'Create stream'}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
