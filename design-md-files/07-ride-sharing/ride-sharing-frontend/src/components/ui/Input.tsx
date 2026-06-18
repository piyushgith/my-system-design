import type { InputHTMLAttributes } from 'react'

type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  label?: string
  error?: string
}

export const Input = ({ label, error, className = '', id, ...props }: InputProps) => {
  const inputId = id ?? props.name

  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <label htmlFor={inputId} className="text-xs font-medium uppercase tracking-wider text-muted">
          {label}
        </label>
      )}
      <input
        id={inputId}
        className={`w-full rounded-xl border border-border bg-surface px-4 py-3 text-cream placeholder:text-muted/60 outline-none transition focus:border-amber-500 focus:ring-2 focus:ring-amber-500/20 ${error ? 'border-rose-500' : ''} ${className}`}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${inputId}-error` : undefined}
        {...props}
      />
      {error && (
        <p id={`${inputId}-error`} className="text-xs text-rose-400" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
