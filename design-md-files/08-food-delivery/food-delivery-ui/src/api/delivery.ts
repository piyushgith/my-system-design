import { apiClient } from './axios'
import type { DeliveryResponse, PartnerLocationResponse } from '../types/delivery'

export const deliveryApi = {
  setStatus: (online: boolean) =>
    apiClient.put<void>('/delivery/status', null, { params: { online } }),

  updateLocation: (lat: number, lng: number) =>
    apiClient.put<void>('/delivery/location', null, { params: { lat, lng } }),

  assign: (orderId: string, partnerId: string) =>
    apiClient.post<DeliveryResponse>('/delivery/assignments', null, { params: { orderId, partnerId } }),

  markPickedUp: (orderId: string) =>
    apiClient.put<void>(`/delivery/trips/${orderId}/pickup`),

  markDelivered: (orderId: string) =>
    apiClient.put<void>(`/delivery/trips/${orderId}/delivered`),

  getDriverLocation: (orderId: string) =>
    apiClient.get<PartnerLocationResponse>(`/delivery/orders/${orderId}/location`),
}
