import { useState } from 'react'
import { api } from '../api.js'

const MODES = ['BROADCAST', 'AUTO_RECOVER', 'AUTO_SCALE']
const RESUME_STRATEGIES = ['NONE', 'PER_EVENT', 'PER_BATCH', 'INTERVAL']
const FULL_DOCUMENT = ['', 'DEFAULT', 'UPDATE_LOOKUP', 'WHEN_AVAILABLE', 'REQUIRED']
const FULL_DOCUMENT_BEFORE_CHANGE = ['', 'DEFAULT', 'OFF', 'WHEN_AVAILABLE', 'REQUIRED']

// The listener bean that consumes an output pipeline, and the attribute key that
// names it (ChangeStreamConfig.attributes). Kept in sync with the backend
// MaterializedViewListener.BEAN_NAME / ATTR_OUTPUT_PIPELINE constants.
const MATERIALIZED_VIEW_LISTENER = 'materializedViewListener'
const ATTR_OUTPUT_PIPELINE = 'outputPipeline'

/**
 * Create / edit form for a change stream definition
 * (persisted through ChangeStreamConfigService on the backend).
 */
export default function StreamForm({ initial, listeners, pipelines = [], onSaved, onCancel }) {
  const editing = Boolean(initial?.id)
  // Keep the full attributes map so we round-trip any listener-defined keys we
  // don't surface in the form; we only edit outputPipeline here.
  const initialAttributes = initial?.attributes ?? {}
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
    outputPipeline: initialAttributes[ATTR_OUTPUT_PIPELINE] ?? '',
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
    // The materialized-view listener requires an output pipeline to run.
    if (form.listener === MATERIALIZED_VIEW_LISTENER && !form.outputPipeline) {
      setError('Output pipeline is required for the materializedViewListener.')
      return
    }
    // Preserve any listener-defined attributes we don't edit here. The output
    // pipeline only applies to the materialized-view listener; set it when that
    // listener is selected, otherwise drop it so it doesn't linger on the config.
    const attributes = { ...initialAttributes }
    if (form.listener === MATERIALIZED_VIEW_LISTENER && form.outputPipeline)
      attributes[ATTR_OUTPUT_PIPELINE] = form.outputPipeline
    else delete attributes[ATTR_OUTPUT_PIPELINE]

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
        attributes: Object.keys(attributes).length ? attributes : null,
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
        {form.listener === MATERIALIZED_VIEW_LISTENER && (
          <label>
            Output pipeline <small>(required for this listener)</small>
            <select value={form.outputPipeline} onChange={set('outputPipeline')}>
              <option value="">(none)</option>
              {form.outputPipeline && !pipelines.includes(form.outputPipeline) && (
                <option value={form.outputPipeline}>{form.outputPipeline}</option>
              )}
              {pipelines.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </label>
        )}
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
