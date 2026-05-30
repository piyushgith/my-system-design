import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface CartItem {
  menuItemId: string
  name: string
  priceAmount: number
  priceCurrency: string
  quantity: number
  customizations?: string
}

interface CartState {
  restaurantId: string | null
  restaurantName: string | null
  items: CartItem[]
  addItem: (restaurantId: string, restaurantName: string, item: CartItem) => void
  removeItem: (menuItemId: string) => void
  updateQuantity: (menuItemId: string, quantity: number) => void
  clearCart: () => void
  totalAmount: () => number
  totalItems: () => number
}

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      restaurantId: null,
      restaurantName: null,
      items: [],

      addItem: (restaurantId, restaurantName, item) => {
        const state = get()

        // switching restaurants clears cart
        if (state.restaurantId && state.restaurantId !== restaurantId) {
          set({ restaurantId, restaurantName, items: [{ ...item, quantity: 1 }] })
          return
        }

        const existing = state.items.find((i) => i.menuItemId === item.menuItemId)
        if (existing) {
          set({
            items: state.items.map((i) =>
              i.menuItemId === item.menuItemId ? { ...i, quantity: i.quantity + 1 } : i
            ),
          })
        } else {
          set({
            restaurantId,
            restaurantName,
            items: [...state.items, { ...item, quantity: 1 }],
          })
        }
      },

      removeItem: (menuItemId) => {
        const items = get().items.filter((i) => i.menuItemId !== menuItemId)
        set({ items, restaurantId: items.length === 0 ? null : get().restaurantId })
      },

      updateQuantity: (menuItemId, quantity) => {
        if (quantity <= 0) {
          get().removeItem(menuItemId)
          return
        }
        set({ items: get().items.map((i) => (i.menuItemId === menuItemId ? { ...i, quantity } : i)) })
      },

      clearCart: () => set({ restaurantId: null, restaurantName: null, items: [] }),

      totalAmount: () => get().items.reduce((sum, i) => sum + i.priceAmount * i.quantity, 0),

      totalItems: () => get().items.reduce((sum, i) => sum + i.quantity, 0),
    }),
    { name: 'fd_cart' }
  )
)
