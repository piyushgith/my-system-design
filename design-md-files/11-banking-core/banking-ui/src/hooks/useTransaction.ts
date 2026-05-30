import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { transactionApi } from '../api/transactionApi'
import type { DepositRequest, StatementRequest, TransferRequest } from '../types'
import { generateIdempotencyKey } from '../utils/idempotency'

export function useTransactionHistory(
  accountId: string | undefined,
  params: { fromDate?: string; toDate?: string; page?: number; size?: number }
) {
  return useQuery({
    queryKey: ['transactions', accountId, params],
    queryFn: () => transactionApi.getHistory(accountId!, params),
    enabled: !!accountId,
  })
}

export function useDeposit() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: DepositRequest) =>
      transactionApi.deposit(data, generateIdempotencyKey()),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: ['account', variables.accountId] })
      qc.invalidateQueries({ queryKey: ['balance', variables.accountId] })
      qc.invalidateQueries({ queryKey: ['transactions', variables.accountId] })
    },
  })
}

export function useTransfer() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: TransferRequest) =>
      transactionApi.transfer(data, generateIdempotencyKey()),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: ['account', variables.fromAccountId] })
      qc.invalidateQueries({ queryKey: ['balance', variables.fromAccountId] })
      qc.invalidateQueries({ queryKey: ['transactions', variables.fromAccountId] })
      qc.invalidateQueries({ queryKey: ['account', variables.toAccountId] })
      qc.invalidateQueries({ queryKey: ['balance', variables.toAccountId] })
      qc.invalidateQueries({ queryKey: ['transactions', variables.toAccountId] })
    },
  })
}

export function useStatement(accountId: string | undefined, request: StatementRequest | null) {
  return useQuery({
    queryKey: ['statement', accountId, request],
    queryFn: () => transactionApi.getStatement(accountId!, request!),
    enabled: !!accountId && !!request,
  })
}
