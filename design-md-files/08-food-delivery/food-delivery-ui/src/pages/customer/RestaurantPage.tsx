import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { restaurantsApi } from '../../api/restaurants'
import { MenuItemCard } from '../../components/MenuItemCard'
import { formatCurrency } from '../../utils/currency'
import { PageSpinner } from '../../components/ui/Spinner'

export function RestaurantPage() {
  const { restaurantId } = useParams<{ restaurantId: string }>()

  const { data: restaurant, isLoading: loadingRestaurant } = useQuery({
    queryKey: ['restaurant', restaurantId],
    queryFn: () => restaurantsApi.getById(restaurantId!).then((r) => r.data),
    enabled: !!restaurantId,
  })

  const { data: menu, isLoading: loadingMenu } = useQuery({
    queryKey: ['menu', restaurantId],
    queryFn: () => restaurantsApi.getMenu(restaurantId!).then((r) => r.data),
    enabled: !!restaurantId,
  })

  if (loadingRestaurant || loadingMenu) return <PageSpinner />

  if (!restaurant) return <p className="text-center text-red-500 py-10">Restaurant not found.</p>

  return (
    <div className="space-y-6">
      <div className="bg-white rounded-xl overflow-hidden shadow-sm border border-gray-100">
        <div className="h-40 bg-orange-50 relative">
          {restaurant.logoUrl ? (
            <img src={restaurant.logoUrl} alt={restaurant.name} className="w-full h-full object-cover" />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-5xl">🍽️</div>
          )}
          {!restaurant.isOpen && (
            <div className="absolute inset-0 bg-black/50 flex items-center justify-center">
              <span className="text-white font-semibold bg-black/70 px-4 py-1 rounded-full">
                Currently Closed
              </span>
            </div>
          )}
        </div>
        <div className="p-4">
          <h1 className="text-xl font-bold text-gray-900">{restaurant.name}</h1>
          <p className="text-sm text-gray-500 mt-0.5">{restaurant.cuisineTypes.join(', ')}</p>
          {restaurant.description && (
            <p className="text-sm text-gray-600 mt-2">{restaurant.description}</p>
          )}
          <div className="flex flex-wrap items-center gap-3 mt-3 text-sm text-gray-600">
            <span className="flex items-center gap-1">
              <span className="text-yellow-500">★</span>
              <span className="font-medium">{restaurant.rating.toFixed(1)}</span>
              <span className="text-gray-400">({restaurant.totalRatings})</span>
            </span>
            <span className="text-gray-300">|</span>
            <span>{restaurant.avgPrepTimeMinutes} min prep</span>
            <span className="text-gray-300">|</span>
            <span>Min order {formatCurrency(restaurant.minimumOrderAmount)}</span>
          </div>
        </div>
      </div>

      {menu && menu.categories.length === 0 && (
        <p className="text-center text-gray-400 py-8">No menu items available.</p>
      )}

      {menu && menu.categories.map((category) => (
        <section key={category.id} className="space-y-3">
          <h2 className="font-semibold text-gray-900 text-base border-b pb-2">{category.name}</h2>
          <div className="space-y-2">
            {category.items.map((item) => (
              <MenuItemCard
                key={item.id}
                item={item}
                restaurantId={restaurant.id}
                restaurantName={restaurant.name}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}
