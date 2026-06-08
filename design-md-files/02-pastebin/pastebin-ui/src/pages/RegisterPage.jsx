import { useReducer } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { register as apiRegister } from '../api/auth'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

const initialState = { email: '', displayName: '', password: '', loading: false, error: '' }

function reducer(state, action) {
  switch (action.type) {
    case 'SET_FIELD': return { ...state, [action.field]: action.value }
    case 'SUBMIT_START': return { ...state, loading: true, error: '' }
    case 'SUBMIT_ERROR': return { ...state, loading: false, error: action.error }
    case 'SUBMIT_DONE': return { ...state, loading: false }
    default: return state
  }
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const { addToast } = useToast()
  const [state, dispatch] = useReducer(reducer, initialState)
  const { email, displayName, password, loading, error } = state

  async function handleSubmit(e) {
    e.preventDefault()
    if (password.length < 8) {
      dispatch({ type: 'SUBMIT_ERROR', error: 'Password must be at least 8 characters' })
      return
    }
    dispatch({ type: 'SUBMIT_START' })
    try {
      const res = await apiRegister(email, password, displayName)
      const { token, userId, email: userEmail, displayName: dn } = res.data
      login({ id: userId, email: userEmail, displayName: dn }, token)
      addToast('Account created!', 'success')
      navigate('/')
    } catch (err) {
      const detail = err.response?.data?.detail || err.response?.data?.message || 'Registration failed'
      dispatch({ type: 'SUBMIT_ERROR', error: detail })
    } finally {
      dispatch({ type: 'SUBMIT_DONE' })
    }
  }

  return (
    <div className="page auth-page">
      <div className="auth-card">
        <div className="card card-body">
          <h1>Create Account</h1>
          {error && <div className="alert alert-error">{error}</div>}
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="reg-display-name">Display Name</label>
              <input
                id="reg-display-name"
                type="text"
                value={displayName}
                onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'displayName', value: e.target.value })}
                required
                placeholder="piyush"
              />
            </div>
            <div className="form-group">
              <label htmlFor="reg-email">Email</label>
              <input
                id="reg-email"
                type="email"
                value={email}
                onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'email', value: e.target.value })}
                required
                placeholder="you@example.com"
              />
            </div>
            <div className="form-group">
              <label htmlFor="reg-password">Password</label>
              <input
                id="reg-password"
                type="password"
                value={password}
                onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'password', value: e.target.value })}
                required
                minLength={8}
                placeholder="Min 8 characters"
              />
            </div>
            <button type="submit" className="btn btn-primary" style={{ width: '100%' }} disabled={loading}>
              {loading ? <><span className="spinner" /> Creating account…</> : 'Register'}
            </button>
          </form>
          <div className="divider" />
          <p style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
            Have an account? <Link to="/login">Login</Link>
          </p>
        </div>
      </div>
    </div>
  )
}
