import { useState, useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { deliveryApi } from '../../api/delivery'
import { useToastStore } from '../../store/toastStore'
import { Button } from '../../components/ui/Button'

export function DeliveryDashboardPage() {
  const { addToast } = useToastStore()
  const [isOnline, setIsOnline] = useState(false)
  const [activeOrderId, setActiveOrderId] = useState('')
  const [orderInput, setOrderInput] = useState('')

  const statusMutation = useMutation({
    mutationFn: (online: boolean) => deliveryApi.setStatus(online),
    onSuccess: (_, online) => {
      setIsOnline(online)
      addToast(online ? 'You are now online' : 'You are now offline', 'success')
    },
    onError: () => addToast('Failed to update status', 'error'),
  })

  const pickupMutation = useMutation({
    mutationFn: () => deliveryApi.markPickedUp(activeOrderId),
    onSuccess: () => addToast('Marked as picked up', 'success'),
    onError: () => addToast('Failed to update', 'error'),
  })

  const deliveredMutation = useMutation({
    mutationFn: () => deliveryApi.markDelivered(activeOrderId),
    onSuccess: () => {
      addToast('Order delivered!', 'success')
      setActiveOrderId('')
      setOrderInput('')
    },
    onError: () => addToast('Failed to update', 'error'),
  })

  useEffect(() => {
    if (!isOnline || !navigator.geolocation) return

    const id = navigator.geolocation.watchPosition(
      (pos) => {
        deliveryApi.updateLocation(pos.coords.latitude, pos.coords.longitude).catch(() => {})
      },
      () => {},
      { enableHighAccuracy: true }
    )

    return () => navigator.geolocation.clearWatch(id)
  }, [isOnline])

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">Delivery Dashboard</h1>
        <div className="flex items-center gap-2">
          <span
            className={`w-2.5 h-2.5 rounded-full ${isOnline ? 'bg-green-500' : 'bg-gray-400'}`}
          />
          <span className="text-sm font-medium text-gray-700">
            {isOnline ? 'Online' : 'Offline'}
          </span>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-100 p-5 space-y-3">
        <h2 className="font-semibold text-gray-700 text-sm">Availability</h2>
        <Button
          variant={isOnline ? 'danger' : 'primary'}
          loading={statusMutation.isPending}
          onClick={() => statusMutation.mutate(!isOnline)}
          className="w-full"
        >
          {isOnline ? 'Go Offline' : 'Go Online'}
        </Button>
      </div>

      {isOnline && (
        <div className="bg-white rounded-xl border border-gray-100 p-5 space-y-4">
          <h2 className="font-semibold text-gray-700 text-sm">Active Trip</h2>

          {!activeOrderId ? (
            <div className="space-y-2">
              <p className="text-sm text-gray-500">Enter your assigned order ID to manage the trip.</p>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={orderInput}
                  onChange={(e) => setOrderInput(e.target.value)}
                  placeholder="Order ID"
                  className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:border-orange-500"
                />
                <Button
                  variant="primary"
                  disabled={!orderInput.trim()}
                  onClick={() => setActiveOrderId(orderInput.trim())}
                >
                  Set
                </Button>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              <p className="text-sm font-medium text-gray-900">
                Order: <span className="text-orange-500">{activeOrderId.slice(-8).toUpperCase()}</span>
              </p>
              <div className="flex gap-2">
                <Button
                  variant="primary"
                  loading={pickupMutation.isPending}
                  onClick={() => pickupMutation.mutate()}
                  className="flex-1"
                >
                  Picked Up
                </Button>
                <Button
                  variant="secondary"
                  loading={deliveredMutation.isPending}
                  onClick={() => deliveredMutation.mutate()}
                  className="flex-1"
                >
                  Delivered
                </Button>
              </div>
              <button
                onClick={() => { setActiveOrderId(''); setOrderInput('') }}
                className="text-sm text-gray-400 hover:text-gray-600 transition-colors"
              >
                Clear trip
              </button>
            </div>
          )}
        </div>
      )}

      {isOnline && (
        <p className="text-xs text-center text-gray-400">
          Location is being shared automatically while online.
        </p>
      )}
    </div>
  )
}
