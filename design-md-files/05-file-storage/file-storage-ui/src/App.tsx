import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { VaultPage } from '@/pages/VaultPage'
import { ToastContainer } from '@/components/ui/ToastContainer'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 15_000,
    },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <VaultPage />
      <ToastContainer />
    </QueryClientProvider>
  )
}
