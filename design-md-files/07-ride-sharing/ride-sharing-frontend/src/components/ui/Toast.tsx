import { useUiStore } from '@/store/ui.store'

export const ToastContainer = () => {
  const toasts = useUiStore((s) => s.toasts)
  const removeToast = useUiStore((s) => s.removeToast)

  if (toasts.length === 0) return null

  return (
    <div className="fixed bottom-24 right-4 z-50 flex max-w-sm flex-col gap-2 lg:bottom-6" aria-live="polite">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`animate-slide-up rounded-xl border px-4 py-3 text-sm shadow-xl backdrop-blur ${
            toast.type === 'error'
              ? 'border-rose-500/50 bg-rose-950/90 text-rose-100'
              : toast.type === 'success'
                ? 'border-emerald-500/50 bg-emerald-950/90 text-emerald-100'
                : 'border-border bg-surface-elevated text-cream'
          }`}
        >
          <div className="flex items-start justify-between gap-3">
            <p>{toast.message}</p>
            <button
              type="button"
              onClick={() => removeToast(toast.id)}
              className="text-muted hover:text-cream"
              aria-label="Dismiss notification"
            >
              ×
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
