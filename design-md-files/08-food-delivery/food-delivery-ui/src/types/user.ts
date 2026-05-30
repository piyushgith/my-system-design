export interface UserProfileResponse {
  id: string
  name: string
  email: string
  phone: string
  loyaltyPoints: number
}

export interface AddressResponse {
  id: string
  label: string
  fullAddress: string
  city: string
  pinCode: string
  latitude: number
  longitude: number
  landmark: string | null
  isDefault: boolean
}

export interface AddAddressRequest {
  label: string
  fullAddress: string
  city: string
  pinCode: string
  country?: string
  latitude: number
  longitude: number
  landmark?: string
  isDefault: boolean
}

export interface UpdateProfileRequest {
  name?: string
  email?: string
}
