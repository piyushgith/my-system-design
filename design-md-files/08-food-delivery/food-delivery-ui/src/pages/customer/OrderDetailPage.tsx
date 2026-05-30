import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ordersApi } from '../../api/orders'
import { OrderStatusBadge } from '../../components/OrderStatusBadge'
import { formatCurrency } from '../../utils/currency'
import { formatDateTime } from '../../utils/datetime'
import { CANCELLABLE_STATUSES, TERMINAL_STATUSES } from '../../types/order'
import { Button } from '../../components/ui/Button'
import { PageSpinner } from '../../components/ui/Spinner'
import { useToastStore } from '../../store/toastStore'

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { addToast } = useToastStore()

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => ordersApi.getOrder(orderId!).then((r) => r.data),
    enabled: !!orderId,
    refetchInterval: (query) =>
      query.state.data && TERMINAL_STATUSES.includes(query.state.data.status) ? false : 5000,
  })

  const cancelMutation = useMutation({
    mutationFn: () => ordersApi.cancelOrder(orderId!, 'Customer requested cancellation'),
    onSuccess: () => {
      addToast('Order cancelled', 'success')
      qc.invalidateQueries({ queryKey: ['order', orderId] })
    },
    onError: () => addToast('Cannot cancel order at this stage', 'error'),
  })

  if (isLoading) return <PageSpinner />
  if (!order) return <p className="text-center text-red-500 py-10">Order not found.</p>

  const canCancel = CANCELLABLE_STATUSES.includes(order.status)

  return (
    <div className="max-w-xl mx-auto space-y-4">
      <button
        onClick={() => navigate('/orders')}
        className="text-sm text-gray-500 hover:text-orange-500 flex items-center gap-1 transition-colors"
      >
        ← Back to Orders
      </button>

      <div className="bg-white rounded-xl border border-gray-100 p-5 space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h1 className="font-semibold text-gray-900">
              Order #{order.id.slice(-8).toUpperCase()}
            </h1>
            <p className="text-xs text-gray-400 mt-0.5">{formatDateTime(order.createdAt)}</p>
          </div>
          <OrderStatusBadge status={order.status} size="md" />
        </div>

        <div className="space-y-2">
          <h2 className="text-sm font-semibold text-gray-700">Items</h2>
          {order.items.map((item) => (
            <div key={item.menuItemId} className="flex justify-between text-sm">
              <span className="text-gray-700">
                {item.name} × {item.quantity}
              </span>
              <span className="text-gray-900 font-medium">
                {formatCurrency(item.totalPrice, order.currency)}
              </span>
            </div>
          ))}
        </div>

        <div className="border-t pt-3 space-y-1.5 text-sm">
          <div className="flex justify-between text-gray-600">
            <span>Subtotal</span>
            <span>{formatCurrency(order.subtotalAmount, order.currency)}</span>
          </div>
          <div className="flex justify-between text-gray-600">
            <span>Delivery fee</span>
            <span>{formatCurrency(order.deliveryFeeAmount, order.currency)}</span>
          </div>
          {order.discountAmount > 0 && (
            <div className="flex justify-between text-green-600">
              <span>Discount</span>
              <span>− {formatCurrency(order.discountAmount, order.currency)}</span>
            </div>
          )}
          <div className="flex justify-between font-semibold text-gray-900 pt-1 border-t">
            <span>Total</span>
            <span>{formatCurrency(order.totalAmount, order.currency)}</span>
          </div>
        </div>

        {order.specialInstructions && (
          <div className="text-sm">
            <p className="font-medium text-gray-700">Special instructions</p>
            <p className="text-gray-500">{order.specialInstructions}</p>
          </div>
        )}

        <div className="text-sm">
          <p className="font-medium text-gray-700">Payment</p>
          <p className="text-gray-500">{order.paymentMethod.replace(/_/g, ' ')}</p>
        </div>

        {order.estimatedDeliveryTime && !order.actualDeliveryTime && (
          <div className="text-sm">
            <p className="font-medium text-gray-700">Estimated delivery</p>
            <p className="text-gray-500">{formatDateTime(order.estimatedDeliveryTime)}</p>
          </div>
        )}

        {order.actualDeliveryTime && (
          <div className="text-sm">
            <p className="font-medium text-gray-700">Delivered at</p>
            <p className="text-green-600">{formatDateTime(order.actualDeliveryTime)}</p>
          </div>
        )}

        {canCancel && (
          <Button
            variant="danger"
            className="w-full"
            loading={cancelMutation.isPending}
            onClick={() => cancelMutation.mutate()}
          >
            Cancel Order
          </Button>
        )}
      </div>
    </div>
  )
}
