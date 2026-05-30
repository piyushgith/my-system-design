interface BadgeProps {
  label: string
  variant?: 'green' | 'red' | 'orange' | 'blue' | 'gray' | 'purple'
  size?: 'sm' | 'md'
}

const variants = {
  green: 'bg-green-100 text-green-700',
  red: 'bg-red-100 text-red-700',
  orange: 'bg-orange-100 text-orange-700',
  blue: 'bg-blue-100 text-blue-700',
  gray: 'bg-gray-100 text-gray-600',
  purple: 'bg-purple-100 text-purple-700',
}

const sizes = {
  sm: 'px-2 py-0.5 text-xs',
  md: 'px-2.5 py-1 text-sm',
}

export function Badge({ label, variant = 'gray', size = 'sm' }: BadgeProps) {
  return (
    <span className={`inline-block font-medium rounded-full ${variants[variant]} ${sizes[size]}`}>
      {label}
    </span>
  )
}
