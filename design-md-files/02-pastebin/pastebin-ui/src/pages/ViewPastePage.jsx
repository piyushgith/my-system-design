import { useState, useEffect } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getPaste, deletePaste } from '../api/pastes'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import CodeViewer from '../components/CodeViewer'

function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function AccessBadge({ level }) {
  const cls = { PUBLIC: 'badge-public', UNLISTED: 'badge-unlisted', PRIVATE: 'badge-private' }
  return <span className={`badge ${cls[level] || 'badge-public'}`}>{level}</span>
}

export default function ViewPastePage() {
  const { key } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { addToast } = useToast()

  const [paste, setPaste] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [passwordInput, setPasswordInput] = useState('')
  const [passwordRequired, setPasswordRequired] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    fetchPaste()
  }, [key])

  async function fetchPaste(password) {
    setLoading(true)
    setError('')
    try {
      const res = await getPaste(key, password)
      setPaste(res.data)
      setPasswordRequired(false)
    } catch (err) {
      const status = err.response?.status
      if (status === 403) {
        setPasswordRequired(true)
        setError('This paste is password-protected.')
      } else if (status === 410) {
        setError('This paste has expired or been deleted.')
      } else if (status === 404) {
        setError('Paste not found.')
      } else {
        setError(err.response?.data?.detail || 'Failed to load paste.')
      }
    } finally {
      setLoading(false)
    }
  }

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
          <button className="btn btn-secondary">Back to Home</button>
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
              <label>Password</label>
              <input
                type="password"
                value={passwordInput}
                onChange={(e) => setPasswordInput(e.target.value)}
                autoFocus
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
            <button className="btn btn-secondary btn-sm" onClick={handleCopy}>
              {copied ? '✓ Copied' : 'Copy'}
            </button>
            <a href={`/raw/${key}`} target="_blank" rel="noreferrer">
              <button className="btn btn-secondary btn-sm">Raw</button>
            </a>
            <Link to="/" state={{ fork: paste }}>
              <button className="btn btn-secondary btn-sm">Fork</button>
            </Link>
            {isOwner && (
              <button className="btn btn-danger btn-sm" onClick={handleDelete} disabled={deleting}>
                {deleting ? 'Deleting…' : 'Delete'}
              </button>
            )}
          </div>
        </div>
        <CodeViewer content={paste.content || ''} language={paste.language} />
      </div>
    </div>
  )
}
