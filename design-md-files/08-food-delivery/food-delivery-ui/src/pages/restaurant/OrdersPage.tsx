import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ordersApi } from '../../api/orders'
import { OrderStatusBadge } from '../../components/OrderStatusBadge'
import { formatCurrency } from '../../utils/currency'
import { formatDateTime } from '../../utils/datetime'
import { Button } from '../../components/ui/Button'
import { PageSpinner } from '../../components/ui/Spinner'
import { useToastStore } from '../../store/toastStore'
import { useAuthStore } from '../../store/authStore'
import type { OrderStatus } from '../../types/order'

const ACTIVE_STATUSES: OrderStatus[] = [
  'RESTAURANT_NOTIFIED',
  'RESTAURANT_ACCEPTED',
  'FOOD_BEING_PREPARED',
  'FOOD_READY',
]

export function RestaurantOrdersPage() {
  const { userId } = useAuthStore()
  const { addToast } = useToastStore()
  const qc = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<OrderStatus | ''>('')

  const { data, isLoading } = useQuery({
    queryKey: ['restaurant-orders', userId, statusFilter],
    queryFn: () =>
      ordersApi
        .getRestaurantOrders({
          restaurantId: userId!,
          status: statusFilter || undefined,
          size: 20,
        })
        .then((r) => r.data),
    enabled: !!userId,
    refetchInterval: 10000,
  })

  const acceptMutation = useMutation({
    mutationFn: (orderId: string) => ordersApi.acceptOrder(orderId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['restaurant-orders'] })
      addToast('Order accepted', 'success')
    },
    onError: () => addToast('Failed to accept order', 'error'),
  })

  const rejectMutation = useMutation({
    mutationFn: (orderId: string) => ordersApi.rejectOrder(orderId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['restaurant-orders'] })
      addToast('Order rejected', 'success')
    },
    onError: () => addToast('Failed to reject order', 'error'),
  })

  const readyMutation = useMutation({
    mutationFn: (orderId: string) => ordersApi.markReady(orderId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['restaurant-orders'] })
      addToast('Marked as ready', 'success')
    },
    onError: () => addToast('Failed to update status', 'error'),
  })

  if (isLoading) return <PageSpinner />

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row sm:items-center gap-3">
        <h1 className="text-xl font-bold text-gray-900 flex-1">Incoming Orders</h1>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as OrderStatus | '')}
          className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:border-orange-500"
        >
          <option value="">All statuses</option>
          {ACTIVE_STATUSES.map((s) => (
            <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
          ))}
        </select>
      </div>

      {data && data.content.length === 0 && (
        <p className="text-center text-gray-400 py-10">No orders.</p>
      )}

      {data && data.content.map((order) => (
        <div
          key={order.id}
          className="bg-white rounded-xl border border-gray-100 p-4 space-y-3"
        >
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-semibold text-gray-900">
                Order #{order.id.slice(-8).toUpperCase()}
              </p>
              <p className="text-xs text-gray-400">{formatDateTime(order.createdAt)}</p>
            </div>
            <div className="flex flex-col items-end gap-1">
              <OrderStatusBadge status={order.status} size="md" />
              <p className="text-sm font-semibold">{formatCurrency(order.totalAmount, order.currency)}</p>
            </div>
          </div>

          <div className="space-y-1">
            {order.items.map((item) => (
              <p key={item.menuItemId} className="text-sm text-gray-700">
                {item.name} × {item.quantity}
                {item.customizations && (
                  <span className="text-gray-400"> ({item.customizations})</span>
                )}
              </p>
            ))}
          </div>

          {order.specialInstructions && (
            <p className="text-xs text-orange-600 bg-orange-50 px-3 py-2 rounded-lg">
              Note: {order.specialInstructions}
            </p>
          )}

          <div className="flex gap-2 pt-1">
            {order.status === 'RESTAURANT_NOTIFIED' && (
              <>
                <Button
                  variant="primary"
                  loading={acceptMutation.isPending}
                  onClick={() => acceptMutation.mutate(order.id)}
                >
                  Accept
                </Button>
                <Button
                  variant="danger"
                  loading={rejectMutation.isPending}
                  onClick={() => rejectMutation.mutate(order.id)}
                >
                  Reject
                </Button>
              </>
            )}
            {(order.status === 'RESTAURANT_ACCEPTED' || order.status === 'FOOD_BEING_PREPARED') && (
              <Button
                variant="primary"
                loading={readyMutation.isPending}
                onClick={() => readyMutation.mutate(order.id)}
              >
                Mark Ready
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
