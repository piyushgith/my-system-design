type BadgeVariant = 'green' | 'red' | 'yellow' | 'blue' | 'gray' | 'purple'

interface BadgeProps {
  children: string
  variant?: BadgeVariant
}

const variantMap: Record<string, BadgeVariant> = {
  ACTIVE: 'green',
  VERIFIED: 'green',
  KYC_VERIFIED: 'green',
  COMPLETED: 'green',
  POSTED: 'green',
  INACTIVE: 'gray',
  CLOSED: 'gray',
  RELEASED: 'gray',
  FROZEN: 'blue',
  PENDING: 'yellow',
  KYC_PENDING: 'yellow',
  FAILED: 'red',
  REJECTED: 'red',
  HELD: 'red',
}

const colors: Record<BadgeVariant, string> = {
  green: 'bg-green-100 text-green-700',
  red: 'bg-red-100 text-red-700',
  yellow: 'bg-yellow-100 text-yellow-700',
  blue: 'bg-blue-100 text-blue-700',
  gray: 'bg-slate-100 text-slate-600',
  purple: 'bg-purple-100 text-purple-700',
}

export function Badge({ children, variant }: BadgeProps) {
  const resolved = variant ?? variantMap[children] ?? 'gray'
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${colors[resolved]}`}>
      {children.replace(/_/g, ' ')}
    </span>
  )
}
