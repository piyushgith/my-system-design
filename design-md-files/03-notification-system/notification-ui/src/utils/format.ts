import { format, formatDistanceToNow, parseISO } from 'date-fns'

export function formatDateTime(iso: string): string {
  try {
    return format(parseISO(iso), 'MMM d, yyyy HH:mm:ss')
  } catch {
    return iso
  }
}

export function formatRelative(iso: string): string {
  try {
    return formatDistanceToNow(parseISO(iso), { addSuffix: true })
  } catch {
    return iso
  }
}

export function formatTime(hhmm: string): string {
  return hhmm
}

export function truncate(s: string, max = 80): string {
  return s.length > max ? s.slice(0, max) + '…' : s
}
