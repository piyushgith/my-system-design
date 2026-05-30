export type DeliveryStatus = 'ASSIGNED' | 'ACCEPTED' | 'AT_RESTAURANT' | 'PICKED_UP' | 'DELIVERED' | 'FAILED'

export interface DeliveryResponse {
  id: string
  orderId: string
  partnerId: string
  status: DeliveryStatus
  deliveryFeeAmount: number
  partnerEarningAmount: number
}

export interface PartnerLocationResponse {
  latitude: number
  longitude: number
  available: boolean
}
