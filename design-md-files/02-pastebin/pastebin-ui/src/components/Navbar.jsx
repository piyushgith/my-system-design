import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function Navbar() {
  const { user, logout, isAuthenticated } = useAuth()
  const navigate = useNavigate()

  function handleLogout() {
    logout()
    navigate('/')
  }

  return (
    <nav className="navbar">
      <Link to="/" className="navbar-brand">
        📋 Pastebin
      </Link>
      <div className="navbar-nav">
        {isAuthenticated ? (
          <>
            <Link to="/me/pastes">
              <button className="btn btn-ghost btn-sm">My Pastes</button>
            </Link>
            <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>
              {user?.displayName || user?.email}
            </span>
            <button className="btn btn-secondary btn-sm" onClick={handleLogout}>
              Logout
            </button>
          </>
        ) : (
          <>
            <Link to="/login">
              <button className="btn btn-ghost btn-sm">Login</button>
            </Link>
            <Link to="/register">
              <button className="btn btn-primary btn-sm">Register</button>
            </Link>
          </>
        )}
      </div>
    </nav>
  )
}
