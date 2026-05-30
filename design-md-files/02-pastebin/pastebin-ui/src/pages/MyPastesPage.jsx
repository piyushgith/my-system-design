import { useState, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listMyPastes, deletePaste } from '../api/pastes'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString()
}

function formatSize(bytes) {
  if (!bytes) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const ACCESS_COLORS = {
  PUBLIC: 'badge-public',
  UNLISTED: 'badge-unlisted',
  PRIVATE: 'badge-private',
}

export default function MyPastesPage() {
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const { addToast } = useToast()

  const [pastes, setPastes] = useState([])
  const [cursor, setCursor] = useState(null)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [includeExpired, setIncludeExpired] = useState(false)
  const [deleting, setDeleting] = useState(null)

  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login')
      return
    }
    loadPastes(null, false)
  }, [isAuthenticated, includeExpired])

  async function loadPastes(cursorParam, append) {
    setLoading(true)
    setError('')
    try {
      const res = await listMyPastes({
        cursor: cursorParam,
        limit: 20,
        includeExpired,
      })
      const data = res.data
      setPastes((prev) => append ? [...prev, ...data.items] : data.items)
      setCursor(data.cursor)
      setHasMore(data.hasMore)
    } catch (err) {
      setError(err.response?.data?.detail || 'Failed to load pastes')
    } finally {
      setLoading(false)
    }
  }

  async function handleDelete(key) {
    if (!confirm('Delete this paste permanently?')) return
    setDeleting(key)
    try {
      await deletePaste(key)
      setPastes((prev) => prev.filter((p) => p.shortKey !== key))
      addToast('Paste deleted', 'success')
    } catch {
      addToast('Failed to delete', 'error')
    } finally {
      setDeleting(null)
    }
  }

  function loadMore() {
    loadPastes(cursor, true)
  }

  return (
    <div className="page">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
        <h1 style={{ fontSize: 20, fontWeight: 600 }}>My Pastes</h1>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', color: 'var(--text-muted)' }}>
            <input
              type="checkbox"
              checked={includeExpired}
              onChange={(e) => setIncludeExpired(e.target.checked)}
              style={{ width: 'auto' }}
            />
            Show expired
          </label>
          <Link to="/">
            <button className="btn btn-primary btn-sm">+ New Paste</button>
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        {loading && pastes.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <span className="spinner" />
          </div>
        ) : pastes.length === 0 ? (
          <div className="empty-state">
            <h3>No pastes yet</h3>
            <p>Create your first paste to see it here.</p>
          </div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Title / Key</th>
                  <th>Language</th>
                  <th>Access</th>
                  <th>Size</th>
                  <th>Views</th>
                  <th>Created</th>
                  <th>Expires</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {pastes.map((p) => (
                  <tr key={p.shortKey}>
                    <td>
                      <Link to={`/p/${p.shortKey}`} style={{ fontWeight: 500 }}>
                        {p.title || p.shortKey}
                      </Link>
                      {!p.title && (
                        <span style={{ color: 'var(--text-subtle)', fontSize: 11, marginLeft: 6 }}>
                          {p.shortKey}
                        </span>
                      )}
                    </td>
                    <td>
                      <span className="badge badge-lang">{p.language}</span>
                    </td>
                    <td>
                      <span className={`badge ${ACCESS_COLORS[p.accessLevel] || 'badge-public'}`}>
                        {p.accessLevel}
                      </span>
                    </td>
                    <td style={{ color: 'var(--text-muted)' }}>{formatSize(p.size)}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{p.viewCount ?? 0}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{formatDate(p.createdAt)}</td>
                    <td style={{ color: 'var(--text-muted)' }}>
                      {p.expiresAt ? formatDate(p.expiresAt) : 'Never'}
                    </td>
                    <td>
                      <button
                        className="btn btn-danger btn-sm"
                        onClick={() => handleDelete(p.shortKey)}
                        disabled={deleting === p.shortKey}
                      >
                        {deleting === p.shortKey ? '…' : 'Delete'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {hasMore && (
        <div className="pagination">
          <button className="btn btn-secondary" onClick={loadMore} disabled={loading}>
            {loading ? <><span className="spinner" /> Loading…</> : 'Load more'}
          </button>
        </div>
      )}
    </div>
  )
}
