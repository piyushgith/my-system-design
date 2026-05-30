import { useQuery, useMutation } from '@tanstack/react-query'
import { restaurantsApi } from '../../api/restaurants'
import { useAuthStore } from '../../store/authStore'
import { useToastStore } from '../../store/toastStore'
import { PageSpinner } from '../../components/ui/Spinner'
import { formatCurrency } from '../../utils/currency'

export function RestaurantMenuPage() {
  const { userId } = useAuthStore()
  const { addToast } = useToastStore()

  const { data: menu, isLoading, refetch } = useQuery({
    queryKey: ['restaurant-menu', userId],
    queryFn: () => restaurantsApi.getMenu(userId!).then((r) => r.data),
    enabled: !!userId,
  })

  const toggleMutation = useMutation({
    mutationFn: ({ itemId, isAvailable }: { itemId: string; isAvailable: boolean }) =>
      restaurantsApi.toggleMenuItemAvailability(userId!, itemId, isAvailable),
    onSuccess: () => {
      refetch()
      addToast('Menu item updated', 'success')
    },
    onError: () => addToast('Failed to update item', 'error'),
  })

  if (isLoading) return <PageSpinner />

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-gray-900">Menu Management</h1>

      {menu && menu.categories.map((cat) => (
        <section key={cat.id} className="space-y-2">
          <h2 className="font-semibold text-gray-700 border-b pb-2">{cat.name}</h2>
          {cat.items.map((item) => (
            <div
              key={item.id}
              className="flex items-center gap-3 bg-white rounded-lg border border-gray-100 p-3"
            >
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-900">{item.name}</p>
                <p className="text-xs text-gray-500">
                  {formatCurrency(item.discountedPriceAmount ?? item.priceAmount, item.priceCurrency)}
                  {item.discountedPriceAmount && (
                    <span className="ml-1 line-through text-gray-400">
                      {formatCurrency(item.priceAmount, item.priceCurrency)}
                    </span>
                  )}
                </p>
              </div>
              <label className="flex items-center gap-2 cursor-pointer flex-shrink-0">
                <span className="text-xs text-gray-500">
                  {item.isVegetarian ? 'Veg' : 'Non-veg'}
                </span>
                <button
                  onClick={() =>
                    toggleMutation.mutate({ itemId: item.id, isAvailable: !item.isAvailable })
                  }
                  disabled={toggleMutation.isPending}
                  className="text-xs px-3 py-1 rounded-full border transition-colors
                    border-green-500 text-green-600 hover:bg-green-50 disabled:opacity-50"
                >
                  Toggle Available
                </button>
              </label>
            </div>
          ))}
        </section>
      ))}
    </div>
  )
}
