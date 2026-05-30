import { apiClient } from './axios'
import type { RestaurantSummary, RestaurantDetail, MenuResponse, PageResponse } from '../types/restaurant'

export const restaurantsApi = {
  browse: (params: { cityId: string; isOpen?: boolean; cuisine?: string; page?: number; size?: number }) =>
    apiClient.get<PageResponse<RestaurantSummary>>('/restaurants', { params }),

  getById: (restaurantId: string) =>
    apiClient.get<RestaurantDetail>(`/restaurants/${restaurantId}`),

  getMenu: (restaurantId: string) =>
    apiClient.get<MenuResponse>(`/restaurants/${restaurantId}/menu`),

  updateAvailability: (restaurantId: string, isOpen: boolean) =>
    apiClient.put<void>('/restaurant/availability', null, { params: { restaurantId, isOpen } }),

  toggleMenuItemAvailability: (restaurantId: string, itemId: string, isAvailable: boolean) =>
    apiClient.put<void>(`/restaurant/menu/items/${itemId}/availability`, null, {
      params: { restaurantId, isAvailable },
    }),
}
