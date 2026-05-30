export interface RestaurantSummary {
  id: string
  name: string
  cuisineTypes: string[]
  rating: number
  totalRatings: number
  avgPrepTimeMinutes: number
  minimumOrderAmount: number
  isOpen: boolean
  logoUrl: string | null
}

export interface RestaurantDetail {
  id: string
  name: string
  description: string | null
  cuisineTypes: string[]
  rating: number
  totalRatings: number
  avgPrepTimeMinutes: number
  minimumOrderAmount: number
  isOpen: boolean
  cityId: string
  latitude: number
  longitude: number
  deliveryRadiusMeters: number
  logoUrl: string | null
}

export interface MenuItemResponse {
  id: string
  name: string
  description: string | null
  priceAmount: number
  priceCurrency: string
  discountedPriceAmount: number | null
  isVegetarian: boolean
  isVegan: boolean
  prepTimeMinutes: number
  tags: string[]
  allergens: string[]
  imageUrl: string | null
}

export interface MenuCategoryResponse {
  id: string
  name: string
  displayOrder: number
  items: MenuItemResponse[]
}

export interface MenuResponse {
  restaurantId: string
  restaurantName: string
  categories: MenuCategoryResponse[]
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  last: boolean
}
