import type { ReactNode } from 'react'

type CardProps = {
  children: ReactNode
  className?: string
  title?: string
  subtitle?: string
}

export const Card = ({ children, className = '', title, subtitle }: CardProps) => (
  <div className={`rounded-2xl border border-border bg-surface/80 p-5 backdrop-blur-sm ${className}`}>
    {(title || subtitle) && (
      <div className="mb-4">
        {title && <h3 className="font-display text-lg font-bold text-cream">{title}</h3>}
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
    )}
    {children}
  </div>
)
