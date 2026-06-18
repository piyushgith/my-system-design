import { useEffect, useState } from 'react'
import { DEMO_PICKUP } from '@/constants/demo'

type GeoPosition = { lat: number; lng: number }

export const useGeolocation = (fallback: GeoPosition = DEMO_PICKUP) => {
  const [position, setPosition] = useState<GeoPosition>(fallback)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!navigator.geolocation) {
      setError('Geolocation not supported — using demo coordinates')
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setPosition({ lat: pos.coords.latitude, lng: pos.coords.longitude })
      },
      () => {
        setError('Location denied — using demo coordinates')
        setPosition(fallback)
      },
      { enableHighAccuracy: true, timeout: 8000 },
    )
  }, [fallback.lat, fallback.lng])

  return { position, error }
}
