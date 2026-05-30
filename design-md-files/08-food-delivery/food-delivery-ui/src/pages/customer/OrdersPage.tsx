import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { ordersApi } from '../../api/orders'
import { OrderStatusBadge } from '../../components/OrderStatusBadge'
import { formatCurrency } from '../../utils/currency'
import { formatDateTime } from '../../utils/datetime'
import { PageSpinner } from '../../components/ui/Spinner'

export function OrdersPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['orders', page],
    queryFn: () => ordersApi.listOrders({ page, size: 10 }).then((r) => r.data),
    placeholderData: keepPreviousData,
  })

  if (isLoading) return <PageSpinner />

  if (isError) {
    return <p className="text-center text-red-500 py-10">Failed to load orders.</p>
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-gray-900">Your Orders</h1>

      {data && data.content.length === 0 && (
        <div className="text-center py-16 text-gray-400">
          <p className="text-4xl mb-3">🍽️</p>
          <p>No orders yet. Start exploring restaurants!</p>
        </div>
      )}

      {data && data.content.map((order) => (
        <Link
          key={order.id}
          to={`/orders/${order.id}`}
          className="block bg-white rounded-xl border border-gray-100 p-4 hover:shadow-md transition-shadow"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex-1 min-w-0">
              <p className="text-sm text-gray-500 mb-1">{formatDateTime(order.createdAt)}</p>
              <p className="text-sm font-medium text-gray-700 truncate">Order #{order.id.slice(-8).toUpperCase()}</p>
            </div>
            <div className="flex flex-col items-end gap-1 flex-shrink-0">
              <OrderStatusBadge status={order.status} />
              <p className="text-sm font-semibold text-gray-900">
                {formatCurrency(order.totalAmount, order.currency)}
              </p>
            </div>
          </div>
        </Link>
      ))}

      {data && data.totalPages > 1 && (
        <div className="flex justify-center gap-2 pt-2">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="px-4 py-2 text-sm border rounded-lg disabled:opacity-40 hover:bg-gray-100 transition-colors"
          >
            Previous
          </button>
          <span className="px-4 py-2 text-sm text-gray-600">
            {page + 1} / {data.totalPages}
          </span>
          <button
            disabled={data.last}
            onClick={() => setPage((p) => p + 1)}
            className="px-4 py-2 text-sm border rounded-lg disabled:opacity-40 hover:bg-gray-100 transition-colors"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
