export type OrderStatus =
  | 'PAYMENT_PENDING'
  | 'PAYMENT_CONFIRMED'
  | 'PAYMENT_FAILED'
  | 'RESTAURANT_NOTIFIED'
  | 'RESTAURANT_ACCEPTED'
  | 'RESTAURANT_REJECTED'
  | 'FOOD_BEING_PREPARED'
  | 'FOOD_READY'
  | 'DELIVERY_PARTNER_ASSIGNED'
  | 'PARTNER_AT_RESTAURANT'
  | 'PICKED_UP'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLING'
  | 'CANCELLED'

export const TERMINAL_STATUSES: OrderStatus[] = ['DELIVERED', 'CANCELLED', 'PAYMENT_FAILED']
export const CANCELLABLE_STATUSES: OrderStatus[] = [
  'PAYMENT_PENDING',
  'PAYMENT_CONFIRMED',
  'RESTAURANT_NOTIFIED',
  'RESTAURANT_ACCEPTED',
]

export interface OrderItemDetail {
  menuItemId: string
  name: string
  unitPrice: number
  quantity: number
  totalPrice: number
  customizations: string | null
}

export interface OrderDetailResponse {
  id: string
  customerId: string
  restaurantId: string
  status: OrderStatus
  items: OrderItemDetail[]
  subtotalAmount: number
  deliveryFeeAmount: number
  discountAmount: number
  totalAmount: number
  currency: string
  paymentMethod: string
  estimatedDeliveryTime: string
  actualDeliveryTime: string | null
  specialInstructions: string | null
  createdAt: string
}

export interface OrderSummary {
  id: string
  restaurantId: string
  status: OrderStatus
  totalAmount: number
  currency: string
  createdAt: string
}

export interface OrderItemRequest {
  menuItemId: string
  quantity: number
  customizations?: string
}

export interface PlaceOrderRequest {
  restaurantId: string
  deliveryAddressId: string
  items: OrderItemRequest[]
  paymentMethod: string
  couponCode?: string
  specialInstructions?: string
  idempotencyKey: string
}

export interface PlaceOrderResponse {
  orderId: string
  status: OrderStatus
  totalAmount: number
  currency: string
  estimatedDeliveryTime: string
  trackingUrl: string
}

export interface CancelOrderResponse {
  orderId: string
  status: OrderStatus
  message: string
}
