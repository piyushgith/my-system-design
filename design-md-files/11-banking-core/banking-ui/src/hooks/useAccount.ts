import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { accountApi } from '../api/accountApi'
import type { OpenAccountRequest } from '../types'
import { generateIdempotencyKey } from '../utils/idempotency'

export function useAccount(accountId: string | undefined) {
  return useQuery({
    queryKey: ['account', accountId],
    queryFn: () => accountApi.get(accountId!),
    enabled: !!accountId,
  })
}

export function useBalance(accountId: string | undefined) {
  return useQuery({
    queryKey: ['balance', accountId],
    queryFn: () => accountApi.getBalance(accountId!),
    enabled: !!accountId,
    refetchInterval: 30_000,
  })
}

export function useOpenAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: OpenAccountRequest) =>
      accountApi.open(data, generateIdempotencyKey()),
    onSuccess: (account) => {
      qc.setQueryData(['account', account.accountId], account)
    },
  })
}
