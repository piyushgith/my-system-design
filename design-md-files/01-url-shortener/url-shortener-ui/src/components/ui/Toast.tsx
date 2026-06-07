import { useEffect, useRef, useState } from 'react'

type ToastType = 'success' | 'error' | 'info'

interface ToastProps {
  message: string
  type?: ToastType
  duration?: number
  onClose: () => void
}

const typeClasses: Record<ToastType, string> = {
  success: 'border-brand-300/30 bg-gray-900 text-brand-300',
  error: 'border-red-500/30 bg-gray-900 text-red-400',
  info: 'border-gray-700 bg-gray-900 text-gray-300',
}

export function Toast({ message, type = 'success', duration = 3000, onClose }: Readonly<ToastProps>) {
  const [visible, setVisible] = useState(true)
  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })

  useEffect(() => {
    const timer = setTimeout(() => {
      setVisible(false)
      setTimeout(() => onCloseRef.current(), 300)
    }, duration)
    return () => clearTimeout(timer)
  }, [duration])

  return (
    <output
      aria-live="polite"
      className={[
        'fixed bottom-6 right-6 z-50 flex items-center gap-3 rounded-lg px-4 py-3 border',
        'shadow-2xl font-mono text-sm',
        'transition-all duration-300',
        typeClasses[type],
        visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2',
      ].join(' ')}
    >
      <span>{message}</span>
      <button
        type="button"
        onClick={() => { setVisible(false); setTimeout(onClose, 300) }}
        className="ml-1 rounded p-0.5 text-current opacity-50 hover:opacity-100 transition-opacity"
        aria-label="Dismiss"
      >
        <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22z" />
        </svg>
      </button>
    </output>
  )
}
