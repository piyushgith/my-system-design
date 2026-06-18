export const DEMO_CITY_ID =
  import.meta.env.VITE_DEFAULT_CITY_ID ?? '11111111-1111-1111-1111-111111111111'

export const DEMO_VEHICLE_ID =
  import.meta.env.VITE_DEMO_VEHICLE_ID ?? '44444444-4444-4444-4444-444444444444'

export const DEMO_PICKUP = {
  lat: Number(import.meta.env.VITE_DEMO_PICKUP_LAT ?? 12.9716),
  lng: Number(import.meta.env.VITE_DEMO_PICKUP_LNG ?? 77.5946),
  address: 'MG Road, Bangalore',
}

export const DEMO_DESTINATION = {
  lat: Number(import.meta.env.VITE_DEMO_DEST_LAT ?? 12.9352),
  lng: Number(import.meta.env.VITE_DEMO_DEST_LNG ?? 77.6245),
  address: 'Koramangala, Bangalore',
}

export const API_BASE_URL = import.meta.env.VITE_API_URL ?? ''
export const WS_BASE_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080'

export const MOCK_OTP = '123456'

export const TERMINAL_TRIP_STATUSES = ['COMPLETED', 'CANCELLED'] as const

export const ACTIVE_TRIP_STATUSES = [
  'REQUESTED',
  'MATCHING',
  'DRIVER_MATCHED',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
] as const
