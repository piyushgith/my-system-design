import { useReducer, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createPaste } from '../api/pastes'
import { getLanguages } from '../api/meta'
import { useToast } from '../context/ToastContext'
import { useAuth } from '../context/AuthContext'
import { formatCode, FORMATTABLE_LANGS } from '../utils/formatter'

const EXPIRY_OPTIONS = [
  { value: 'ONE_HOUR', label: '1 Hour' },
  { value: 'ONE_DAY', label: '1 Day' },
  { value: 'ONE_WEEK', label: '1 Week' },
  { value: 'ONE_MONTH', label: '1 Month' },
  { value: 'NEVER', label: 'Never' },
]

const ACCESS_OPTIONS = [
  { value: 'PUBLIC', label: 'Public' },
  { value: 'UNLISTED', label: 'Unlisted' },
  { value: 'PRIVATE', label: 'Private' },
]

const initialState = {
  content: '',
  title: '',
  language: 'plaintext',
  expiryPolicy: 'ONE_WEEK',
  accessLevel: 'PUBLIC',
  customAlias: '',
  password: '',
  languages: [],
  loading: false,
  error: '',
}

function reducer(state, action) {
  switch (action.type) {
    case 'SET_FIELD': return { ...state, [action.field]: action.value }
    case 'SET_LANGUAGES': return { ...state, languages: action.languages }
    case 'SUBMIT_START': return { ...state, loading: true, error: '' }
    case 'SUBMIT_ERROR': return { ...state, loading: false, error: action.error }
    case 'SUBMIT_DONE': return { ...state, loading: false }
    default: return state
  }
}

export default function HomePage() {
  const navigate = useNavigate()
  const { addToast } = useToast()
  const { isAuthenticated } = useAuth()
  const [state, dispatch] = useReducer(reducer, initialState)
  const { content, title, language, expiryPolicy, accessLevel, customAlias, password, languages, loading, error } = state
  const [formatting, setFormatting] = useState(false)

  async function handleFormat() {
    if (!content.trim() || !FORMATTABLE_LANGS.has(language)) return
    setFormatting(true)
    const { code, error: fmtError } = await formatCode(content, language)
    setFormatting(false)
    if (fmtError) {
      addToast(fmtError, 'error')
    } else {
      dispatch({ type: 'SET_FIELD', field: 'content', value: code })
      addToast('Code formatted', 'success')
    }
  }

  useEffect(() => {
    getLanguages()
      .then((res) => dispatch({ type: 'SET_LANGUAGES', languages: res.data.languages || [] }))
      .catch(() => {
        dispatch({
          type: 'SET_LANGUAGES',
          languages: [
            { id: 'plaintext', label: 'Plain Text' },
            { id: 'java', label: 'Java' },
            { id: 'python', label: 'Python' },
            { id: 'javascript', label: 'JavaScript' },
            { id: 'typescript', label: 'TypeScript' },
            { id: 'yaml', label: 'YAML' },
            { id: 'json', label: 'JSON' },
            { id: 'sql', label: 'SQL' },
            { id: 'bash', label: 'Bash' },
            { id: 'kotlin', label: 'Kotlin' },
            { id: 'go', label: 'Go' },
            { id: 'rust', label: 'Rust' },
          ],
        })
      })
  }, [])

  async function handleSubmit(e) {
    e.preventDefault()
    if (!content.trim()) {
      dispatch({ type: 'SUBMIT_ERROR', error: 'Content is required' })
      return
    }
    dispatch({ type: 'SUBMIT_START' })
    try {
      const payload = {
        title: title || null,
        content,
        language,
        expiryPolicy,
        accessLevel,
        password: password || null,
        customAlias: customAlias || null,
      }
      const res = await createPaste(payload)
      const { shortKey } = res.data
      addToast('Paste created!', 'success')
      navigate(`/p/${shortKey}`)
    } catch (err) {
      const detail = err.response?.data?.detail || err.response?.data?.message || 'Failed to create paste'
      dispatch({ type: 'SUBMIT_ERROR', error: detail })
    } finally {
      dispatch({ type: 'SUBMIT_DONE' })
    }
  }

  return (
    <div className="page">
      <form onSubmit={handleSubmit}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
          <h1 style={{ fontSize: 20, fontWeight: 600 }}>New Paste</h1>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? <><span className="spinner" /> Creating…</> : 'Create Paste'}
          </button>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-header">
            <input
              type="text"
              aria-label="Paste title"
              placeholder="Paste title (optional)"
              value={title}
              onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'title', value: e.target.value })}
              maxLength={255}
              style={{ flex: 1, background: 'transparent', border: 'none', fontSize: 15, fontWeight: 500, padding: '4px 0' }}
            />
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexShrink: 0 }}>
              <select
                value={language}
                onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'language', value: e.target.value })}
                style={{ width: 140 }}
              >
                {languages.map((l) => (
                  <option key={l.id} value={l.id}>{l.label}</option>
                ))}
              </select>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={handleFormat}
                disabled={formatting || !content.trim() || !FORMATTABLE_LANGS.has(language)}
                title={FORMATTABLE_LANGS.has(language) ? 'Format code' : `No formatter for ${language}`}
              >
                {formatting ? <><span className="spinner" /> Formatting…</> : '⌥ Format'}
              </button>
            </div>
          </div>
          <textarea
            aria-label="Paste content"
            value={content}
            onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'content', value: e.target.value })}
            placeholder="Paste your code or text here…"
            style={{
              border: 'none',
              borderRadius: 0,
              minHeight: 400,
              resize: 'vertical',
              background: 'transparent',
            }}
            spellCheck={false}
          />
        </div>

        <div className="row">
          <div className="form-group">
            <label htmlFor="expiry-policy">Expiry</label>
            <select id="expiry-policy" value={expiryPolicy} onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'expiryPolicy', value: e.target.value })}>
              {EXPIRY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label htmlFor="access-level">Access</label>
            <select id="access-level" value={accessLevel} onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'accessLevel', value: e.target.value })}>
              {ACCESS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>

          {isAuthenticated && (
            <div className="form-group">
              <label htmlFor="custom-alias">Custom Alias (optional)</label>
              <input
                id="custom-alias"
                type="text"
                placeholder="my-snippet"
                value={customAlias}
                onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'customAlias', value: e.target.value })}
                maxLength={32}
              />
            </div>
          )}

          <div className="form-group">
            <label htmlFor="paste-password">Password (optional)</label>
            <input
              id="paste-password"
              type="password"
              placeholder="Protect with password"
              value={password}
              onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'password', value: e.target.value })}
              maxLength={72}
            />
          </div>
        </div>
      </form>
    </div>
  )
}
