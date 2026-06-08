import { useReducer, useEffect, useRef, useCallback, useState } from 'react'
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

const initialState = { pastes: [], hasMore: false, loading: true, error: '' }

function reducer(state, action) {
  switch (action.type) {
    case 'LOAD_START':
      return { ...state, loading: true, error: '' }
    case 'LOAD_SUCCESS':
      return {
        ...state,
        loading: false,
        pastes: action.append ? [...state.pastes, ...action.items] : action.items,
        hasMore: action.hasMore,
      }
    case 'LOAD_ERROR':
      return { ...state, loading: false, error: action.error }
    case 'DELETE_PASTE':
      return { ...state, pastes: state.pastes.filter((p) => p.shortKey !== action.key) }
    default:
      return state
  }
}

export default function MyPastesPage() {
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const { addToast } = useToast()

  const [state, dispatch] = useReducer(reducer, initialState)
  const { pastes, hasMore, loading, error } = state

  const [includeExpired, setIncludeExpired] = useState(false)
  const [deleting, setDeleting] = useState(null)
  const cursorRef = useRef(null)

  const loadPastes = useCallback(async (cursorParam, append) => {
    dispatch({ type: 'LOAD_START' })
    try {
      const res = await listMyPastes({ cursor: cursorParam, limit: 20, includeExpired })
      const data = res.data
      cursorRef.current = data.cursor
      dispatch({ type: 'LOAD_SUCCESS', items: data.items, hasMore: data.hasMore, append })
    } catch (err) {
      dispatch({ type: 'LOAD_ERROR', error: err.response?.data?.detail || 'Failed to load pastes' })
    }
  }, [includeExpired])

  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login')
      return
    }
    loadPastes(null, false)
  }, [isAuthenticated, navigate, loadPastes])

  async function handleDelete(key) {
    if (!confirm('Delete this paste permanently?')) return
    setDeleting(key)
    try {
      await deletePaste(key)
      dispatch({ type: 'DELETE_PASTE', key })
      addToast('Paste deleted', 'success')
    } catch {
      addToast('Failed to delete', 'error')
    } finally {
      setDeleting(null)
    }
  }

  function loadMore() {
    loadPastes(cursorRef.current, true)
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
            />{' '}
            Show expired
          </label>
          <Link to="/">
            <button type="button" className="btn btn-primary btn-sm">+ New Paste</button>
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        {loading && pastes.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <span className="spinner" />
          </div>
        ) : null}
        {!loading && pastes.length === 0 ? (
          <div className="empty-state">
            <h3>No pastes yet</h3>
            <p>Create your first paste to see it here.</p>
          </div>
        ) : null}
        {pastes.length > 0 ? (
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
                  <th aria-label="Actions"></th>
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
                        <span style={{ color: 'var(--text-subtle)', fontSize: 12, marginLeft: 6 }}>
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
                        type="button"
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
        ) : null}
      </div>

      {hasMore && (
        <div className="pagination">
          <button type="button" className="btn btn-secondary" onClick={loadMore} disabled={loading}>
            {loading ? <><span className="spinner" /> Loading…</> : 'Load more'}
          </button>
        </div>
      )}
    </div>
  )
}
