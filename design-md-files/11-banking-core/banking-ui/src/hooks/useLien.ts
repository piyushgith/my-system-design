import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { lienApi } from '../api/lienApi'
import type { PlaceLienRequest } from '../types'

export function useLiens(accountId: string | undefined) {
  return useQuery({
    queryKey: ['liens', accountId],
    queryFn: () => lienApi.list(accountId!),
    enabled: !!accountId,
  })
}

export function usePlaceLien(accountId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: PlaceLienRequest) => lienApi.place(accountId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['liens', accountId] })
      qc.invalidateQueries({ queryKey: ['account', accountId] })
      qc.invalidateQueries({ queryKey: ['balance', accountId] })
    },
  })
}

export function useReleaseLien(accountId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (lienId: string) => lienApi.release(accountId, lienId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['liens', accountId] })
      qc.invalidateQueries({ queryKey: ['account', accountId] })
      qc.invalidateQueries({ queryKey: ['balance', accountId] })
    },
  })
}
