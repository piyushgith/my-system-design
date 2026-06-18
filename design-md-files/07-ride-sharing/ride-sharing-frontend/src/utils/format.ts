export const formatInr = (amount: number | null | undefined): string => {
  if (amount == null) return '—'
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount)
}

export const formatDate = (iso: string | null | undefined): string => {
  if (!iso) return '—'
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(iso))
}

export const formatDuration = (minutes: number | null | undefined): string => {
  if (minutes == null) return '—'
  return `${minutes} min`
}

export const formatDistance = (km: number | null | undefined): string => {
  if (km == null) return '—'
  return `${Number(km).toFixed(1)} km`
}
