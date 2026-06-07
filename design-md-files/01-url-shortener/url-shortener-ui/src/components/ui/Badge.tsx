type BadgeVariant = 'green' | 'red' | 'yellow' | 'blue' | 'gray'

interface BadgeProps {
  variant?: BadgeVariant
  children: React.ReactNode
}

const variantClasses: Record<BadgeVariant, string> = {
  green: 'bg-green-500/10 text-green-400 border border-green-500/20',
  red: 'bg-red-500/10 text-red-400 border border-red-500/20',
  yellow: 'bg-yellow-500/10 text-yellow-400 border border-yellow-500/20',
  blue: 'bg-blue-500/10 text-blue-400 border border-blue-500/20',
  gray: 'bg-gray-800 text-gray-400 border border-gray-700',
}

export function Badge({ variant = 'gray', children }: Readonly<BadgeProps>) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-mono ${variantClasses[variant]}`}
    >
      {children}
    </span>
  )
}
