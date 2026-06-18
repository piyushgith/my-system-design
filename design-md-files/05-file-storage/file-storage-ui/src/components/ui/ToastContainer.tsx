import { useToastStore } from '@/store/toastStore'

const variantStyles = {
  success: 'border-vault-teal/40 bg-vault-teal/10 text-vault-teal',
  error: 'border-vault-danger/40 bg-vault-danger/10 text-vault-danger',
  info: 'border-vault-border bg-vault-panel text-vault-text',
}

export function ToastContainer() {
  const toasts = useToastStore((s) => s.toasts)
  const dismiss = useToastStore((s) => s.dismiss)

  if (toasts.length === 0) return null

  return (
    <div
      className="pointer-events-none fixed bottom-6 right-6 z-50 flex w-full max-w-sm flex-col gap-3"
      aria-live="polite"
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`pointer-events-auto animate-fade-up rounded-xl border px-4 py-3 text-sm shadow-vault backdrop-blur-md ${variantStyles[toast.variant]}`}
        >
          <div className="flex items-start justify-between gap-3">
            <p>{toast.message}</p>
            <button
              type="button"
              onClick={() => dismiss(toast.id)}
              className="font-mono text-xs opacity-60 transition hover:opacity-100"
              aria-label="Dismiss notification"
            >
              ✕
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
