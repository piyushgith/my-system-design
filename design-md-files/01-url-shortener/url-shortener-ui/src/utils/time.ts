export function formatRelativeTime(isoString: string): string {
  const date = new Date(isoString)
  const now = new Date()
  const diffMs = date.getTime() - now.getTime()
  const diffSec = Math.round(diffMs / 1000)

  if (diffSec < 0) return 'Expired'

  const units: [number, string][] = [
    [86400, 'day'],
    [3600, 'hour'],
    [60, 'minute'],
    [1, 'second'],
  ]

  for (const [seconds, label] of units) {
    const count = Math.floor(diffSec / seconds)
    if (count >= 1) return `${count} ${label}${count !== 1 ? 's' : ''}`
  }

  return 'Less than a second'
}

export function formatDateTime(isoString: string): string {
  return new Date(isoString).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
