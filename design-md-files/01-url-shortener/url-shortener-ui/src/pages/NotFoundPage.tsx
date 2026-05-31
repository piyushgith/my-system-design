import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/Button'

export function NotFoundPage() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[50vh] text-center gap-4">
      <p className="text-6xl font-bold text-brand-200">404</p>
      <h1 className="text-xl font-semibold text-gray-900">Page not found</h1>
      <p className="text-sm text-gray-500 max-w-xs">
        If you were looking for a short URL redirect, those are handled directly by the backend
        at <code className="font-mono text-xs bg-gray-100 px-1 py-0.5 rounded">localhost:8080/{'{shortCode}'}</code>.
      </p>
      <Link to="/">
        <Button>Back to home</Button>
      </Link>
    </div>
  )
}
