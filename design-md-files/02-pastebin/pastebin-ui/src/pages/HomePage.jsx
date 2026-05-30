import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { createPaste } from '../api/pastes'
import { getLanguages } from '../api/meta'
import { useToast } from '../context/ToastContext'
import { useAuth } from '../context/AuthContext'

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

export default function HomePage() {
  const navigate = useNavigate()
  const { addToast } = useToast()
  const { isAuthenticated } = useAuth()

  const [content, setContent] = useState('')
  const [title, setTitle] = useState('')
  const [language, setLanguage] = useState('plaintext')
  const [expiryPolicy, setExpiryPolicy] = useState('ONE_WEEK')
  const [accessLevel, setAccessLevel] = useState('PUBLIC')
  const [customAlias, setCustomAlias] = useState('')
  const [password, setPassword] = useState('')
  const [languages, setLanguages] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    getLanguages()
      .then((res) => setLanguages(res.data.languages || []))
      .catch(() => {
        // fallback list if backend unavailable
        setLanguages([
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
        ])
      })
  }, [])

  async function handleSubmit(e) {
    e.preventDefault()
    if (!content.trim()) {
      setError('Content is required')
      return
    }
    setError('')
    setLoading(true)
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
      setError(detail)
    } finally {
      setLoading(false)
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
              placeholder="Paste title (optional)"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={255}
              style={{ flex: 1, background: 'transparent', border: 'none', fontSize: 15, fontWeight: 500, padding: '4px 0' }}
            />
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexShrink: 0 }}>
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                style={{ width: 140 }}
              >
                {languages.map((l) => (
                  <option key={l.id} value={l.id}>{l.label}</option>
                ))}
              </select>
            </div>
          </div>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
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
            <label>Expiry</label>
            <select value={expiryPolicy} onChange={(e) => setExpiryPolicy(e.target.value)}>
              {EXPIRY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label>Access</label>
            <select value={accessLevel} onChange={(e) => setAccessLevel(e.target.value)}>
              {ACCESS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>

          {isAuthenticated && (
            <div className="form-group">
              <label>Custom Alias (optional)</label>
              <input
                type="text"
                placeholder="my-snippet"
                value={customAlias}
                onChange={(e) => setCustomAlias(e.target.value)}
                maxLength={32}
              />
            </div>
          )}

          <div className="form-group">
            <label>Password (optional)</label>
            <input
              type="password"
              placeholder="Protect with password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              maxLength={72}
            />
          </div>
        </div>
      </form>
    </div>
  )
}
