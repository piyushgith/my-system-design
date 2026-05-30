import clsx from 'clsx'
import type { LeadStatus, DealStatus } from '@/types'

type BadgeVariant = 'green' | 'blue' | 'yellow' | 'red' | 'gray' | 'purple'

interface BadgeProps {
  label: string
  variant?: BadgeVariant
}

const variants: Record<BadgeVariant, string> = {
  green: 'bg-green-100 text-green-800',
  blue: 'bg-blue-100 text-blue-800',
  yellow: 'bg-yellow-100 text-yellow-800',
  red: 'bg-red-100 text-red-800',
  gray: 'bg-gray-100 text-gray-700',
  purple: 'bg-purple-100 text-purple-800',
}

export function Badge({ label, variant = 'gray' }: BadgeProps) {
  return (
    <span className={clsx('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', variants[variant])}>
      {label}
    </span>
  )
}

export function LeadStatusBadge({ status }: { status: LeadStatus }) {
  const map: Record<LeadStatus, BadgeVariant> = {
    NEW: 'blue',
    CONTACTED: 'yellow',
    QUALIFIED: 'purple',
    CONVERTED: 'green',
    LOST: 'red',
  }
  return <Badge label={status} variant={map[status]} />
}

export function DealStatusBadge({ status }: { status: DealStatus }) {
  const map: Record<DealStatus, BadgeVariant> = {
    OPEN: 'blue',
    WON: 'green',
    LOST: 'red',
  }
  return <Badge label={status} variant={map[status]} />
}
