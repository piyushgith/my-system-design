type SpinnerProps = {
  label?: string
  className?: string
}

export const Spinner = ({ label = 'Loading', className = '' }: SpinnerProps) => (
  <div className={`flex flex-col items-center justify-center gap-3 ${className}`} role="status" aria-live="polite">
    <div className="h-10 w-10 animate-spin rounded-full border-2 border-amber-500 border-t-transparent" />
    <span className="text-sm text-muted">{label}</span>
  </div>
)
