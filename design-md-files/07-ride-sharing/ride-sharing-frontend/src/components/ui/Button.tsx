import type { ButtonHTMLAttributes } from 'react'

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  loading?: boolean
}

const variantClasses: Record<ButtonVariant, string> = {
  primary: 'bg-amber-500 text-charcoal hover:bg-amber-400 shadow-glow',
  secondary: 'bg-surface-elevated text-cream border border-border hover:border-amber-500/50',
  ghost: 'text-muted hover:text-cream hover:bg-surface-elevated',
  danger: 'bg-rose-600 text-white hover:bg-rose-500',
}

export const Button = ({
  variant = 'primary',
  loading = false,
  className = '',
  disabled,
  children,
  ...props
}: ButtonProps) => (
  <button
    type="button"
    className={`inline-flex items-center justify-center gap-2 rounded-xl px-5 py-3 text-sm font-semibold transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-50 ${variantClasses[variant]} ${className}`}
    disabled={disabled || loading}
    {...props}
  >
    {loading && (
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" aria-hidden="true" />
    )}
    {children}
  </button>
)
