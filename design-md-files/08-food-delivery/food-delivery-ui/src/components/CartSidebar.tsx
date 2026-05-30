import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useCartStore } from '../store/cartStore'
import { useToastStore } from '../store/toastStore'
import { usersApi } from '../api/users'
import { ordersApi } from '../api/orders'
import { formatCurrency } from '../utils/currency'
import { generateIdempotencyKey } from '../utils/idempotency'
import { Button } from './ui/Button'
import { Spinner } from './ui/Spinner'

interface Props {
  isOpen: boolean
  onClose: () => void
}

export function CartSidebar({ isOpen, onClose }: Props) {
  const navigate = useNavigate()
  const { addToast } = useToastStore()
  const { restaurantId, restaurantName, items, updateQuantity, clearCart, totalAmount } = useCartStore()

  const [selectedAddressId, setSelectedAddressId] = useState<string>('')
  const [specialInstructions, setSpecialInstructions] = useState('')
  const [placing, setPlacing] = useState(false)

  const { data: addresses = [], isLoading: loadingAddresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: () => usersApi.listAddresses().then((r) => r.data),
    enabled: isOpen,
  })

  async function handlePlaceOrder() {
    if (!restaurantId) return
    if (!selectedAddressId) {
      addToast('Select delivery address', 'error')
      return
    }
    if (items.length === 0) {
      addToast('Cart is empty', 'error')
      return
    }

    setPlacing(true)
    try {
      const res = await ordersApi.placeOrder({
        restaurantId,
        deliveryAddressId: selectedAddressId,
        items: items.map((i) => ({
          menuItemId: i.menuItemId,
          quantity: i.quantity,
          customizations: i.customizations,
        })),
        paymentMethod: 'CASH_ON_DELIVERY',
        specialInstructions: specialInstructions || undefined,
        idempotencyKey: generateIdempotencyKey(),
      })
      clearCart()
      onClose()
      navigate(`/orders/${res.data.orderId}`)
    } catch {
      addToast('Failed to place order. Try again.', 'error')
    } finally {
      setPlacing(false)
    }
  }

  return (
    <>
      {isOpen && (
        <div
          className="fixed inset-0 bg-black/40 z-40"
          onClick={onClose}
        />
      )}

      <div
        className={`fixed top-0 right-0 h-full w-full max-w-sm bg-white shadow-xl z-50 flex flex-col transition-transform duration-300 ${
          isOpen ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b">
          <div>
            <h2 className="font-semibold text-gray-900">Your Cart</h2>
            {restaurantName && (
              <p className="text-xs text-gray-500">{restaurantName}</p>
            )}
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          {items.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 text-gray-400">
              <span className="text-4xl mb-2">🛒</span>
              <p className="text-sm">Cart is empty</p>
            </div>
          ) : (
            items.map((item) => (
              <div key={item.menuItemId} className="flex items-center gap-3">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 truncate">{item.name}</p>
                  <p className="text-xs text-gray-500">
                    {formatCurrency(item.priceAmount, item.priceCurrency)} × {item.quantity}
                  </p>
                </div>
                <div className="flex items-center gap-1 bg-orange-500 rounded-lg overflow-hidden flex-shrink-0">
                  <button
                    onClick={() => updateQuantity(item.menuItemId, item.quantity - 1)}
                    className="px-2 py-1 text-white hover:bg-orange-600 font-bold text-sm"
                  >
                    −
                  </button>
                  <span className="px-1 text-white text-sm font-semibold min-w-[20px] text-center">
                    {item.quantity}
                  </span>
                  <button
                    onClick={() => updateQuantity(item.menuItemId, item.quantity + 1)}
                    className="px-2 py-1 text-white hover:bg-orange-600 font-bold text-sm"
                  >
                    +
                  </button>
                </div>
                <p className="text-sm font-semibold text-gray-900 w-16 text-right flex-shrink-0">
                  {formatCurrency(item.priceAmount * item.quantity, item.priceCurrency)}
                </p>
              </div>
            ))
          )}

          {items.length > 0 && (
            <>
              <div className="border-t pt-3 flex justify-between font-semibold text-gray-900">
                <span>Total</span>
                <span>{formatCurrency(totalAmount())}</span>
              </div>

              <div className="pt-2 space-y-2">
                <label className="text-sm font-medium text-gray-700">Delivery Address</label>
                {loadingAddresses ? (
                  <div className="flex items-center gap-2 text-sm text-gray-500">
                    <Spinner size="sm" />
                    Loading addresses...
                  </div>
                ) : addresses.length === 0 ? (
                  <p className="text-sm text-orange-600">
                    No saved address.{' '}
                    <button
                      onClick={() => { onClose(); navigate('/profile') }}
                      className="underline"
                    >
                      Add one
                    </button>
                  </p>
                ) : (
                  <select
                    value={selectedAddressId}
                    onChange={(e) => setSelectedAddressId(e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:border-orange-500"
                  >
                    <option value="">Select address…</option>
                    {addresses.map((addr) => (
                      <option key={addr.id} value={addr.id}>
                        {addr.label} — {addr.fullAddress}
                      </option>
                    ))}
                  </select>
                )}
              </div>

              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">
                  Special Instructions (optional)
                </label>
                <textarea
                  value={specialInstructions}
                  onChange={(e) => setSpecialInstructions(e.target.value)}
                  rows={2}
                  placeholder="E.g. no onions, extra spicy…"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm resize-none focus:outline-none focus:border-orange-500"
                />
              </div>
            </>
          )}
        </div>

        {items.length > 0 && (
          <div className="p-4 border-t space-y-2">
            <Button
              variant="primary"
              className="w-full"
              loading={placing}
              disabled={placing || !selectedAddressId}
              onClick={handlePlaceOrder}
            >
              Place Order · {formatCurrency(totalAmount())}
            </Button>
            <button
              onClick={() => { clearCart(); onClose() }}
              className="w-full text-sm text-gray-400 hover:text-red-500 transition-colors py-1"
            >
              Clear cart
            </button>
          </div>
        )}
      </div>
    </>
  )
}
