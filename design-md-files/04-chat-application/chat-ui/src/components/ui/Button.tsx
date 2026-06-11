import React from 'react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  readonly variant?: 'primary' | 'ghost' | 'danger' | 'outline';
  readonly size?: 'sm' | 'md' | 'lg';
  readonly loading?: boolean;
}

const BASE =
  'inline-flex items-center justify-center gap-2 font-display font-semibold rounded-lg transition-all duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-ch-accent focus-visible:ring-offset-2 focus-visible:ring-offset-ch-base disabled:opacity-40 disabled:cursor-not-allowed select-none';

const VARIANTS = {
  primary:
    'bg-ch-accent text-ch-base hover:bg-ch-accent-dim active:scale-95 shadow-[0_0_16px_rgba(245,158,11,0.25)] hover:shadow-[0_0_24px_rgba(245,158,11,0.35)]',
  ghost:
    'bg-transparent text-ch-muted hover:bg-ch-hover hover:text-ch-text active:scale-95',
  danger:
    'bg-ch-error/10 text-ch-error border border-ch-error/30 hover:bg-ch-error/20 active:scale-95',
  outline:
    'bg-transparent text-ch-text border border-ch-border hover:bg-ch-hover active:scale-95',
};

const SIZES = {
  sm: 'h-7 px-3 text-xs',
  md: 'h-9 px-4 text-sm',
  lg: 'h-11 px-6 text-base',
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  type = 'button',
  disabled,
  children,
  className = '',
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      disabled={disabled || loading}
      className={`${BASE} ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
    >
      {loading && (
        <span className="w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />
      )}
      {children}
    </button>
  );
}
