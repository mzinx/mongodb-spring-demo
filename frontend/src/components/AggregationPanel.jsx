import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * mongodb-spring-aggregation demo: manage pipeline templates (stored in
 * `_pipelines`) and run them with `{"_ph": "variable"}` substitution.
 */
export default function AggregationPanel() {
  const [templates, setTemplates] = useState([])
  const [name, setName] = useState('')
  const [stages, setStages] = useState('[\n  { "$sort": { "createdAt": -1 } },\n  { "$limit": 5 }\n]')
  const [collection, setCollection] = useState('orders')
  const [variables, setVariables] = useState('{}')
  const [results, setResults] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    api.get('/api/pipelines').then(setTemplates).catch((err) => setError(err.message))
  }, [])

  useEffect(load, [load])

  const selectTemplate = (t) => {
    setName(t.name)
    setStages(JSON.stringify(t.aggs ?? [], null, 2))
    setError(null)
  }

  const parse = (label, text) => {
    try {
      return JSON.parse(text)
    } catch (err) {
      throw new Error(`Invalid ${label} JSON: ${err.message}`)
    }
  }

  const save = async () => {
    setError(null)
    try {
      if (!name.trim()) throw new Error('Template name is required')
      await api.put(`/api/pipelines/${encodeURIComponent(name.trim())}`, parse('stages', stages))
      load()
    } catch (err) {
      setError(err.message)
    }
  }

  const remove = async () => {
    if (!name.trim() || !confirm(`Delete pipeline template "${name}"?`)) return
    try {
      await api.del(`/api/pipelines/${encodeURIComponent(name.trim())}`)
      setName('')
      load()
    } catch (err) {
      setError(err.message)
    }
  }

  const run = async () => {
    setError(null)
    setBusy(true)
    try {
      const res = await api.post('/api/aggregations/run', {
        collectionName: collection.trim(),
        stages: parse('stages', stages),
        variables: parse('variables', variables),
      })
      setResults(res)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel split">
      <div className="card">
        <h3>Pipeline templates</h3>
        <p className="hint">
          Stored in the <code>_pipelines</code> collection via <code>PipelineRepository</code>. Click one
          to load it into the editor. <code>{'{"_ph": "status"}'}</code> placeholders are replaced by the
          variables at run time.
        </p>
        <ul className="list">
          {templates.map((t) => (
            <li key={t.name}>
              <button className={`link ${t.name === name ? 'active' : ''}`} onClick={() => selectTemplate(t)}>
                {t.name}
              </button>
            </li>
          ))}
          {templates.length === 0 && <li className="empty">No templates.</li>}
        </ul>

        <label>
          Template name
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="orders-summary" />
        </label>
        <label>
          Stages (JSON array)
          <textarea rows={10} value={stages} onChange={(e) => setStages(e.target.value)} spellCheck={false} />
        </label>
        <div className="actions">
          <button onClick={save}>Save template</button>
          <button className="danger" onClick={remove}>
            Delete template
          </button>
        </div>
      </div>

      <div className="card">
        <h3>Run aggregation</h3>
        <label>
          Collection
          <input value={collection} onChange={(e) => setCollection(e.target.value)} />
        </label>
        <label>
          Variables (JSON object) <small>e.g. {'{"status": "PENDING"}'} for orders-by-status</small>
          <textarea rows={3} value={variables} onChange={(e) => setVariables(e.target.value)} spellCheck={false} />
        </label>
        <div className="actions">
          <button className="primary" onClick={run} disabled={busy}>
            {busy ? 'Running…' : 'Run pipeline from editor'}
          </button>
        </div>

        {error && <p className="error">{error}</p>}

        {results && (
          <>
            <h4>{results.length} result(s)</h4>
            <pre className="results">{JSON.stringify(results, null, 2)}</pre>
          </>
        )}
      </div>
    </div>
  )
}
