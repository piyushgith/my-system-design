import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { usersApi } from '../../api/users'
import { useToastStore } from '../../store/toastStore'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { PageSpinner } from '../../components/ui/Spinner'
import type { AddAddressRequest } from '../../types/user'

export function ProfilePage() {
  const qc = useQueryClient()
  const { addToast } = useToastStore()

  const [showAddAddress, setShowAddAddress] = useState(false)
  const [addrForm, setAddrForm] = useState<AddAddressRequest>({
    label: '',
    fullAddress: '',
    city: '',
    pinCode: '',
    latitude: 0,
    longitude: 0,
    landmark: '',
    isDefault: false,
  })

  const { data: profile, isLoading: loadingProfile } = useQuery({
    queryKey: ['profile'],
    queryFn: () => usersApi.getProfile().then((r) => r.data),
  })

  const { data: addresses = [], isLoading: loadingAddresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: () => usersApi.listAddresses().then((r) => r.data),
  })

  const addAddressMutation = useMutation({
    mutationFn: (data: AddAddressRequest) => usersApi.addAddress(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['addresses'] })
      addToast('Address added', 'success')
      setShowAddAddress(false)
      setAddrForm({ label: '', fullAddress: '', city: '', pinCode: '', latitude: 0, longitude: 0, landmark: '', isDefault: false })
    },
    onError: () => addToast('Failed to add address', 'error'),
  })

  const deleteAddressMutation = useMutation({
    mutationFn: (id: string) => usersApi.deleteAddress(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['addresses'] })
      addToast('Address removed', 'success')
    },
    onError: () => addToast('Failed to remove address', 'error'),
  })

  if (loadingProfile) return <PageSpinner />

  return (
    <div className="max-w-xl mx-auto space-y-6">
      <h1 className="text-xl font-bold text-gray-900">Profile</h1>

      {profile && (
        <div className="bg-white rounded-xl border border-gray-100 p-5 space-y-3">
          <h2 className="font-semibold text-gray-700 text-sm">Account Info</h2>
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-gray-400">Name</p>
              <p className="font-medium text-gray-900">{profile.name}</p>
            </div>
            <div>
              <p className="text-gray-400">Phone</p>
              <p className="font-medium text-gray-900">{profile.phone}</p>
            </div>
            {profile.email && (
              <div>
                <p className="text-gray-400">Email</p>
                <p className="font-medium text-gray-900">{profile.email}</p>
              </div>
            )}
            <div>
              <p className="text-gray-400">Loyalty Points</p>
              <p className="font-medium text-orange-500">{profile.loyaltyPoints}</p>
            </div>
          </div>
        </div>
      )}

      <div className="bg-white rounded-xl border border-gray-100 p-5 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-gray-700 text-sm">Saved Addresses</h2>
          <button
            onClick={() => setShowAddAddress((v) => !v)}
            className="text-sm text-orange-500 font-medium hover:text-orange-600 transition-colors"
          >
            {showAddAddress ? 'Cancel' : '+ Add Address'}
          </button>
        </div>

        {loadingAddresses && <p className="text-sm text-gray-400">Loading…</p>}

        {addresses.length === 0 && !loadingAddresses && (
          <p className="text-sm text-gray-400">No saved addresses.</p>
        )}

        {addresses.map((addr) => (
          <div
            key={addr.id}
            className="flex items-start justify-between gap-3 p-3 bg-gray-50 rounded-lg"
          >
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-gray-900">{addr.label}</p>
              <p className="text-xs text-gray-500 mt-0.5">{addr.fullAddress}, {addr.city} — {addr.pinCode}</p>
              {addr.landmark && <p className="text-xs text-gray-400">{addr.landmark}</p>}
              {addr.isDefault && (
                <span className="text-xs text-green-600 font-medium mt-1 inline-block">Default</span>
              )}
            </div>
            <button
              onClick={() => deleteAddressMutation.mutate(addr.id)}
              className="text-xs text-red-400 hover:text-red-600 flex-shrink-0 transition-colors"
            >
              Remove
            </button>
          </div>
        ))}

        {showAddAddress && (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              addAddressMutation.mutate(addrForm)
            }}
            className="space-y-3 border-t pt-4"
          >
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="Label"
                placeholder="Home / Work"
                value={addrForm.label}
                onChange={(e) => setAddrForm((f) => ({ ...f, label: e.target.value }))}
                required
              />
              <Input
                label="City"
                placeholder="Bengaluru"
                value={addrForm.city}
                onChange={(e) => setAddrForm((f) => ({ ...f, city: e.target.value }))}
                required
              />
            </div>
            <Input
              label="Full Address"
              placeholder="House no, Street, Area"
              value={addrForm.fullAddress}
              onChange={(e) => setAddrForm((f) => ({ ...f, fullAddress: e.target.value }))}
              required
            />
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="Pin Code"
                placeholder="560001"
                value={addrForm.pinCode}
                onChange={(e) => setAddrForm((f) => ({ ...f, pinCode: e.target.value }))}
                required
              />
              <Input
                label="Landmark (optional)"
                placeholder="Near..."
                value={addrForm.landmark ?? ''}
                onChange={(e) => setAddrForm((f) => ({ ...f, landmark: e.target.value || undefined }))}
              />
            </div>
            <label className="flex items-center gap-2 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={addrForm.isDefault}
                onChange={(e) => setAddrForm((f) => ({ ...f, isDefault: e.target.checked }))}
                className="accent-orange-500"
              />
              Set as default
            </label>
            <Button
              type="submit"
              variant="primary"
              className="w-full"
              loading={addAddressMutation.isPending}
            >
              Save Address
            </Button>
          </form>
        )}
      </div>
    </div>
  )
}
