type EmptyStateProps = {
  title: string
  description?: string
  action?: React.ReactNode
}

export const EmptyState = ({ title, description, action }: EmptyStateProps) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-surface-elevated text-2xl">
      ◌
    </div>
    <h3 className="font-display text-lg font-bold text-cream">{title}</h3>
    {description && <p className="mt-2 max-w-xs text-sm text-muted">{description}</p>}
    {action && <div className="mt-6">{action}</div>}
  </div>
)
