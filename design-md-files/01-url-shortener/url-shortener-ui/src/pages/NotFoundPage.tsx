import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/Button'

export function NotFoundPage() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[50vh] text-center gap-5">
      <p className="text-7xl font-extrabold text-brand-300/20 font-display tracking-tight select-none">404</p>
      <h1 className="text-xl font-bold text-gray-200 font-display">Page not found</h1>
      <p className="text-sm text-gray-600 max-w-xs font-mono leading-relaxed">
        {'Short URL redirects are handled by the backend at '}
        <code className="text-gray-500">{'localhost:8080/{shortCode}'}</code>
        {'.'}
      </p>
      <Link to="/">
        <Button>Back to home</Button>
      </Link>
    </div>
  )
}
