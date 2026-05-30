import type { MenuItemResponse } from '../types/restaurant'
import { formatCurrency } from '../utils/currency'
import { useCartStore } from '../store/cartStore'

interface Props {
  item: MenuItemResponse
  restaurantId: string
  restaurantName: string
}

export function MenuItemCard({ item, restaurantId, restaurantName }: Props) {
  const { items, addItem, updateQuantity } = useCartStore()
  const cartItem = items.find((i) => i.menuItemId === item.id)
  const qty = cartItem?.quantity ?? 0

  const effectivePrice = item.discountedPriceAmount ?? item.priceAmount

  return (
    <div className="flex gap-4 p-4 bg-white rounded-lg border border-gray-100 hover:border-orange-200 transition-colors">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-1">
          <span
            className={`w-4 h-4 border-2 rounded-sm flex-shrink-0 flex items-center justify-center text-xs ${
              item.isVegetarian ? 'border-green-600 text-green-600' : 'border-red-600 text-red-600'
            }`}
          >
            {item.isVegetarian ? '●' : '●'}
          </span>
          <h4 className="font-medium text-gray-900 text-sm">{item.name}</h4>
        </div>

        {item.description && (
          <p className="text-xs text-gray-500 line-clamp-2 mb-2">{item.description}</p>
        )}

        <div className="flex items-center gap-2">
          <span className="font-semibold text-gray-900 text-sm">
            {formatCurrency(effectivePrice, item.priceCurrency)}
          </span>
          {item.discountedPriceAmount && (
            <span className="text-xs text-gray-400 line-through">
              {formatCurrency(item.priceAmount, item.priceCurrency)}
            </span>
          )}
        </div>

        {item.tags.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2">
            {item.tags.slice(0, 3).map((tag) => (
              <span key={tag} className="text-xs bg-gray-100 text-gray-500 px-1.5 py-0.5 rounded">
                {tag}
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="flex flex-col items-center justify-between gap-2 flex-shrink-0">
        {item.imageUrl && (
          <img
            src={item.imageUrl}
            alt={item.name}
            className="w-20 h-20 object-cover rounded-lg"
          />
        )}

        {!item.imageUrl && <div className="w-20 h-20 bg-gray-100 rounded-lg" />}

        {qty === 0 ? (
          <button
            onClick={() =>
              addItem(restaurantId, restaurantName, {
                menuItemId: item.id,
                name: item.name,
                priceAmount: effectivePrice,
                priceCurrency: item.priceCurrency,
                quantity: 1,
              })
            }
            className="w-20 py-1 text-sm font-semibold text-orange-500 border border-orange-500 rounded-lg hover:bg-orange-50 transition-colors"
          >
            ADD
          </button>
        ) : (
          <div className="flex items-center gap-1 bg-orange-500 rounded-lg overflow-hidden">
            <button
              onClick={() => updateQuantity(item.id, qty - 1)}
              className="px-2 py-1 text-white hover:bg-orange-600 font-bold"
            >
              −
            </button>
            <span className="px-1 text-white font-semibold text-sm min-w-[20px] text-center">{qty}</span>
            <button
              onClick={() =>
                addItem(restaurantId, restaurantName, {
                  menuItemId: item.id,
                  name: item.name,
                  priceAmount: effectivePrice,
                  priceCurrency: item.priceCurrency,
                  quantity: 1,
                })
              }
              className="px-2 py-1 text-white hover:bg-orange-600 font-bold"
            >
              +
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
