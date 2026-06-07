import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
  hint?: string
  suffix?: ReactNode
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ label, error, hint, id, suffix, className = '', ...rest }, ref) => {
    const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
    let describedBy: string | undefined
    if (inputId && error) describedBy = `${inputId}-error`
    else if (inputId && hint) describedBy = `${inputId}-hint`

    return (
      <div className="flex flex-col gap-1">
        {label && (
          <label htmlFor={inputId} className="text-sm font-medium text-gray-400 font-display">
            {label}
          </label>
        )}
        <div className="relative">
          <input
            ref={ref}
            id={inputId}
            className={[
              'w-full rounded-lg border px-3 py-2 text-sm text-gray-100 bg-gray-900/80',
              'placeholder-gray-700 font-mono',
              'transition-colors duration-150',
              'focus:outline-none focus:ring-2 focus:ring-brand-300/40 focus:border-brand-300/40 input-focus-glow',
              'disabled:bg-gray-900/40 disabled:text-gray-600 disabled:cursor-not-allowed',
              error
                ? 'border-red-500/50 focus:ring-red-500/30 focus:border-red-500/40'
                : 'border-gray-700 hover:border-gray-600',
              suffix ? 'pr-9' : '',
              className,
            ].join(' ')}
            aria-invalid={!!error}
            aria-describedby={describedBy}
            {...rest}
          />
          {suffix && (
            <div className="absolute inset-y-0 right-0 flex items-center pr-2">
              {suffix}
            </div>
          )}
        </div>
        {error && (
          <p id={`${inputId}-error`} className="text-xs text-red-400 font-mono" role="alert">
            ✗ {error}
          </p>
        )}
        {!error && hint && (
          <p id={`${inputId}-hint`} className="text-xs text-gray-600 font-mono">
            {hint}
          </p>
        )}
      </div>
    )
  }
)

Input.displayName = 'Input'
