import type { ButtonHTMLAttributes } from 'react'

type ButtonVariant = 'primary' | 'ghost' | 'danger' | 'brass'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  loading?: boolean
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    'bg-vault-teal text-vault-bg hover:brightness-110 shadow-glow border border-vault-teal/40',
  ghost:
    'bg-transparent text-vault-text border border-vault-border hover:border-vault-teal/50 hover:text-vault-teal',
  danger:
    'bg-vault-danger/10 text-vault-danger border border-vault-danger/30 hover:bg-vault-danger/20',
  brass:
    'bg-vault-brass/10 text-vault-brass border border-vault-brass/30 hover:bg-vault-brass/20',
}

export function Button({
  variant = 'primary',
  loading = false,
  disabled,
  className = '',
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-45 ${variantClasses[variant]} ${className}`}
      {...props}
    >
      {loading ? (
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent"
          aria-hidden="true"
        />
      ) : null}
      {children}
    </button>
  )
}
