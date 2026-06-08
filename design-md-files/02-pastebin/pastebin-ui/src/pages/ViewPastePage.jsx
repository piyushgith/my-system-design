import { useReducer, useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getPaste, deletePaste } from '../api/pastes'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import CodeViewer from '../components/CodeViewer'
import { formatCode, FORMATTABLE_LANGS } from '../utils/formatter'

function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const ACCESS_BADGE_CLS = { PUBLIC: 'badge-public', UNLISTED: 'badge-unlisted', PRIVATE: 'badge-private' }

function AccessBadge({ level }) {
  return <span className={`badge ${ACCESS_BADGE_CLS[level] || 'badge-public'}`}>{level}</span>
}


const initialState = { paste: null, loading: true, error: '', passwordRequired: false, formatting: false, formattedContent: null }

function reducer(state, action) {
  switch (action.type) {
    case 'FETCH_START':
      return { ...state, loading: true, error: '' }
    case 'FETCH_SUCCESS':
      return { ...state, loading: false, paste: action.paste, passwordRequired: false }
    case 'FETCH_PASSWORD_REQUIRED':
      return { ...state, loading: false, passwordRequired: true, error: 'This paste is password-protected.' }
    case 'FETCH_ERROR':
      return { ...state, loading: false, error: action.error }
    case 'FORMAT_START':
      return { ...state, formatting: true }
    case 'FORMAT_SUCCESS':
      return { ...state, formatting: false, formattedContent: action.code }
    case 'FORMAT_RESET':
      return { ...state, formatting: false, formattedContent: null }
    default:
      return state
  }
}

export default function ViewPastePage() {
  const { key } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { addToast } = useToast()

  const [state, dispatch] = useReducer(reducer, initialState)
  const { paste, loading, error, passwordRequired, formatting, formattedContent } = state

  const [passwordInput, setPasswordInput] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [copied, setCopied] = useState(false)

  const fetchPaste = useCallback(async (password) => {
    dispatch({ type: 'FETCH_START' })
    try {
      const res = await getPaste(key, password)
      dispatch({ type: 'FETCH_SUCCESS', paste: res.data })
    } catch (err) {
      const status = err.response?.status
      if (status === 403) {
        dispatch({ type: 'FETCH_PASSWORD_REQUIRED' })
      } else if (status === 410) {
        dispatch({ type: 'FETCH_ERROR', error: 'This paste has expired or been deleted.' })
      } else if (status === 404) {
        dispatch({ type: 'FETCH_ERROR', error: 'Paste not found.' })
      } else {
        dispatch({ type: 'FETCH_ERROR', error: err.response?.data?.detail || 'Failed to load paste.' })
      }
    }
  }, [key])

  useEffect(() => {
    fetchPaste()
  }, [fetchPaste])

  async function handleDelete() {
    if (!confirm('Delete this paste permanently?')) return
    setDeleting(true)
    try {
      await deletePaste(key)
      addToast('Paste deleted', 'success')
      navigate('/')
    } catch {
      addToast('Failed to delete paste', 'error')
    } finally {
      setDeleting(false)
    }
  }

  async function handleCopy() {
    if (!paste?.content) return
    try {
      await navigator.clipboard.writeText(paste.content)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      addToast('Copy failed', 'error')
    }
  }

  async function handleFormat() {
    const raw = paste?.content
    if (!raw || !FORMATTABLE_LANGS.has(paste.language?.toLowerCase())) return
    if (formattedContent !== null) { dispatch({ type: 'FORMAT_RESET' }); return }
    dispatch({ type: 'FORMAT_START' })
    const { code, error: fmtError } = await formatCode(raw, paste.language)
    if (fmtError) { dispatch({ type: 'FORMAT_RESET' }); addToast(fmtError, 'error') }
    else dispatch({ type: 'FORMAT_SUCCESS', code })
  }

  function handlePasswordSubmit(e) {
    e.preventDefault()
    fetchPaste(passwordInput)
  }

  const isOwner = user && paste?.owner?.id && paste.owner.id === user.id

  if (loading) {
    return (
      <div className="page" style={{ textAlign: 'center', paddingTop: 80 }}>
        <span className="spinner" style={{ width: 32, height: 32, borderWidth: 3 }} />
      </div>
    )
  }

  if (error && !passwordRequired) {
    return (
      <div className="page">
        <div className="alert alert-error">{error}</div>
        <Link to="/">
          <button type="button" className="btn btn-secondary">Back to Home</button>
        </Link>
      </div>
    )
  }

  if (passwordRequired) {
    return (
      <div className="page auth-page">
        <div className="auth-card card card-body">
          <h1 style={{ fontSize: 18, marginBottom: 16 }}>🔒 Password Protected</h1>
          {error && <div className="alert alert-error">{error}</div>}
          <form onSubmit={handlePasswordSubmit}>
            <div className="form-group">
              <label htmlFor="paste-password">Password</label>
              <input
                id="paste-password"
                type="password"
                value={passwordInput}
                onChange={(e) => setPasswordInput(e.target.value)}
                placeholder="Enter paste password"
              />
            </div>
            <button type="submit" className="btn btn-primary" style={{ width: '100%' }}>
              Unlock
            </button>
          </form>
        </div>
      </div>
    )
  }

  if (!paste) return null

  return (
    <div className="page page-wide">
      <div className="card">
        <div className="card-header">
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 6 }}>
              {paste.title || paste.shortKey}
            </div>
            <div className="meta-row">
              <span className="badge badge-lang">{paste.language}</span>
              <AccessBadge level={paste.accessLevel} />
              <span className="meta-item">👁 {paste.viewCount ?? 0} views</span>
              <span className="meta-item">📦 {formatSize(paste.size)}</span>
              <span className="meta-item">🕐 {formatDate(paste.createdAt)}</span>
              {paste.expiresAt && (
                <span className="meta-item">⏳ Expires {formatDate(paste.expiresAt)}</span>
              )}
              {paste.owner?.displayName && (
                <span className="meta-item">👤 {paste.owner.displayName}</span>
              )}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
            {FORMATTABLE_LANGS.has(paste.language?.toLowerCase()) && (() => {
              let fmtLabel
              if (formatting) fmtLabel = 'Fmt…'
              else if (formattedContent === null) fmtLabel = '⌥ Format'
              else fmtLabel = '↩ Raw'
              return (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={handleFormat}
                  disabled={formatting}
                  title={formattedContent === null ? 'Format code' : 'Show original'}
                >
                  {formatting && <span className="spinner" />} {fmtLabel}
                </button>
              )
            })()}
            <button type="button" className="btn btn-secondary btn-sm" onClick={handleCopy}>
              {copied ? '✓ Copied' : 'Copy'}
            </button>
            <a href={`/raw/${key}`} target="_blank" rel="noreferrer">
              <button type="button" className="btn btn-secondary btn-sm">Raw</button>
            </a>
            <Link to="/" state={{ fork: paste }}>
              <button type="button" className="btn btn-secondary btn-sm">Fork</button>
            </Link>
            {isOwner && (
              <button type="button" className="btn btn-danger btn-sm" onClick={handleDelete} disabled={deleting}>
                {deleting ? 'Deleting…' : 'Delete'}
              </button>
            )}
          </div>
        </div>
        <CodeViewer content={formattedContent ?? paste.content ?? ''} language={paste.language} />
      </div>
    </div>
  )
}
