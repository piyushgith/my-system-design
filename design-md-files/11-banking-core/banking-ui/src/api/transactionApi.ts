import { apiClient } from './axios'
import type {
  DepositRequest,
  DepositResponse,
  StatementRequest,
  StatementResponse,
  TransactionHistoryResponse,
  TransferRequest,
  TransferResponse,
} from '../types'

export const transactionApi = {
  deposit: (data: DepositRequest, idempotencyKey: string) =>
    apiClient
      .post<DepositResponse>('/transactions/deposit', data, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),

  transfer: (data: TransferRequest, idempotencyKey: string) =>
    apiClient
      .post<TransferResponse>('/transactions/transfer', data, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),

  getHistory: (
    accountId: string,
    params: { fromDate?: string; toDate?: string; page?: number; size?: number }
  ) =>
    apiClient
      .get<TransactionHistoryResponse>(`/accounts/${accountId}/transactions`, { params })
      .then((r) => r.data),

  getStatement: (accountId: string, data: StatementRequest) =>
    apiClient
      .post<StatementResponse>(`/accounts/${accountId}/statements/request`, data)
      .then((r) => r.data),
}
