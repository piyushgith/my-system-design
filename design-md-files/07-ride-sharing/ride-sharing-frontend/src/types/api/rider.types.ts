export type RiderProfileResponse = {
  rider_id: string
  full_name: string
  phone_number: string
  email: string | null
  rating: number
  total_trips: number
  status: string
}

export type UpdateRiderPayload = {
  fullName?: string
  email?: string
}

export type UpdateRiderResponse = {
  rider_id: string
  full_name: string
  email: string | null
}

export type RegisterRiderPayload = {
  phoneNumber: string
  fullName: string
  email?: string
}

export type RegisterRiderResponse = {
  rider_id: string
  status: string
}
