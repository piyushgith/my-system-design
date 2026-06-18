import { Link } from 'react-router-dom'

export const NotFoundPage = () => (
  <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
    <p className="font-display text-6xl font-bold text-amber-500">404</p>
    <h1 className="mt-4 text-xl text-cream">Page not found</h1>
    <Link to="/" className="mt-6 text-sm text-amber-500 hover:underline">
      Back home
    </Link>
  </div>
)
