import { apiClient } from './axios'
import type {
  PlaceOrderRequest,
  PlaceOrderResponse,
  OrderDetailResponse,
  OrderSummary,
  CancelOrderResponse,
  OrderStatus,
} from '../types/order'
import type { PageResponse } from '../types/restaurant'

export const ordersApi = {
  placeOrder: (data: PlaceOrderRequest) =>
    apiClient.post<PlaceOrderResponse>('/orders', data),

  getOrder: (orderId: string) =>
    apiClient.get<OrderDetailResponse>(`/orders/${orderId}`),

  listOrders: (params?: { page?: number; size?: number }) =>
    apiClient.get<PageResponse<OrderSummary>>('/orders', { params }),

  cancelOrder: (orderId: string, reason?: string) =>
    apiClient.post<CancelOrderResponse>(`/orders/${orderId}/cancel`, { reason }),

  // Restaurant partner endpoints
  acceptOrder: (orderId: string, estimatedPrepMinutes = 25) =>
    apiClient.put<void>(`/restaurant/orders/${orderId}/accept`, null, {
      params: { estimatedPrepMinutes },
    }),

  rejectOrder: (orderId: string, reason = 'Unavailable') =>
    apiClient.put<void>(`/restaurant/orders/${orderId}/reject`, null, { params: { reason } }),

  markReady: (orderId: string) =>
    apiClient.put<void>(`/restaurant/orders/${orderId}/ready`, {}),

  getRestaurantOrders: (params: {
    restaurantId: string
    status?: OrderStatus
    page?: number
    size?: number
  }) => apiClient.get<PageResponse<OrderDetailResponse>>('/restaurant/orders', { params }),
}
