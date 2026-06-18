export type UserRole = 'RIDER' | 'DRIVER' | 'ADMIN'

export type VehicleType = 'ECONOMY' | 'PREMIUM' | 'SUV' | 'AUTO' | 'BIKE'

export type TripStatus =
  | 'REQUESTED'
  | 'MATCHING'
  | 'DRIVER_MATCHED'
  | 'DRIVER_ARRIVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'DISPUTED'
  | 'REASSIGNMENT'

export type ApiErrorBody = {
  error: {
    code: string
    message: string
    details: Record<string, unknown>
    request_id: string
    timestamp: string
  }
}

export type PaginationMeta = {
  limit: number
  has_more: boolean
  next_cursor: string | null
}
