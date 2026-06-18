export type WebSocketMessageType =
  | 'CONNECTED'
  | 'TRIP_STATUS'
  | 'DRIVER_LOCATION'
  | 'SYNC_ACK'

export type WebSocketMessage = {
  type: WebSocketMessageType
  data: Record<string, unknown>
}

export type TripStatusEvent = {
  trip_id: string
  status: string
  otp?: string
}

export type DriverLocationEvent = {
  driver_id: string
  lat: number
  lng: number
  heading: number | null
  timestamp: string
}
