import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { customerApi } from '../api/customerApi'
import type { CreateCustomerRequest } from '../types'

export function useCustomer(cifId: string | undefined) {
  return useQuery({
    queryKey: ['customer', cifId],
    queryFn: () => customerApi.get(cifId!),
    enabled: !!cifId,
  })
}

export function useCreateCustomer() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: CreateCustomerRequest) => customerApi.create(data),
    onSuccess: (customer) => {
      qc.setQueryData(['customer', customer.cifId], customer)
    },
  })
}
