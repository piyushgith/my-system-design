import { Link } from 'react-router-dom'
import type { RestaurantSummary } from '../types/restaurant'
import { formatCurrency } from '../utils/currency'

interface Props {
  restaurant: RestaurantSummary
}

export function RestaurantCard({ restaurant }: Props) {
  return (
    <Link
      to={`/restaurants/${restaurant.id}`}
      className="block bg-white rounded-xl shadow-sm hover:shadow-md transition-shadow overflow-hidden border border-gray-100"
    >
      <div className="h-40 bg-gray-200 relative overflow-hidden">
        {restaurant.logoUrl ? (
          <img src={restaurant.logoUrl} alt={restaurant.name} className="w-full h-full object-cover" />
        ) : (
          <div className="w-full h-full flex items-center justify-center bg-orange-50 text-4xl">🍽️</div>
        )}
        {!restaurant.isOpen && (
          <div className="absolute inset-0 bg-black/50 flex items-center justify-center">
            <span className="text-white font-semibold text-sm bg-black/70 px-3 py-1 rounded-full">
              Closed
            </span>
          </div>
        )}
      </div>

      <div className="p-4">
        <h3 className="font-semibold text-gray-900 text-base leading-tight">{restaurant.name}</h3>
        <p className="text-sm text-gray-500 mt-0.5">{restaurant.cuisineTypes.join(', ')}</p>

        <div className="flex items-center gap-3 mt-3 text-sm text-gray-600">
          <span className="flex items-center gap-1">
            <span className="text-yellow-500">★</span>
            <span className="font-medium">{restaurant.rating.toFixed(1)}</span>
            <span className="text-gray-400">({restaurant.totalRatings})</span>
          </span>
          <span className="text-gray-300">|</span>
          <span>{restaurant.avgPrepTimeMinutes} mins</span>
          <span className="text-gray-300">|</span>
          <span>Min {formatCurrency(restaurant.minimumOrderAmount)}</span>
        </div>
      </div>
    </Link>
  )
}
