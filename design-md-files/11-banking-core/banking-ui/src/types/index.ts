// ── Customer / KYC ───────────────────────────────────────────────────────────

export interface CreateCustomerRequest {
  firstName: string
  lastName: string
  dateOfBirth: string // ISO date string "YYYY-MM-DD"
  gender?: string
  pan: string
  aadhaarToken?: string
}

export interface CustomerResponse {
  cifId: string
  firstName: string
  lastName: string
  dateOfBirth: string
  customerStatus: string
  kycStatus: string
  kycVerifiedAt: string | null
}

// ── Account ───────────────────────────────────────────────────────────────────

export interface OpenAccountRequest {
  cifId: string
  accountType: string
  productCode?: string
  initialDeposit?: number
}

export interface AccountResponse {
  accountId: string
  cifId: string
  accountType: string
  status: string
  currency: string
  currentBalance: number
  availableBalance: number
  liensTotal: number
  productCode: string | null
  openDate: string
  kycStatus: string
}

export interface BalanceResponse {
  accountId: string
  currentBalance: number
  availableBalance: number
  currency: string
  balanceAsOf: string
}

// ── Lien ─────────────────────────────────────────────────────────────────────

export interface LienResponse {
  lienId: string
  accountId: string
  amount: number
  reason: string
  status: string
  lienType: string | null
  createdAt: string
}

export interface PlaceLienRequest {
  amount: number
  reason: string
  lienType?: string
  referenceId?: string
}

// ── Transactions ──────────────────────────────────────────────────────────────

export interface DepositRequest {
  accountId: string
  amount: number
  currency?: string
  narration?: string
  referenceNumber?: string
  remitterIfsc?: string
  valueDate?: string
}

export interface DepositResponse {
  txnId: string
  status: string
  accountId: string
  accountBalance: number
  postingDate: string
  valueDate: string
}

export interface TransferRequest {
  fromAccountId: string
  toAccountId: string
  amount: number
  currency?: string
  narration?: string
  valueDate?: string
}

export interface TransferResponse {
  txnId: string
  status: string
  fromAccountBalance: number
  toAccountBalance: number
  postingDate: string
  valueDate: string
}

export interface TransactionLineResponse {
  txnId: string
  type: string
  amount: number
  currency: string
  narration: string | null
  postingDate: string
  valueDate: string
  runningBalance: number
}

export interface TransactionHistoryResponse {
  accountId: string
  transactions: TransactionLineResponse[]
  pagination: {
    page: number
    size: number
    totalElements: number
    totalPages: number
    hasNext: boolean
    truncated: boolean
  }
}

// ── Statement ─────────────────────────────────────────────────────────────────

export interface StatementRequest {
  fromDate: string
  toDate: string
  format?: string
}

export interface StatementLineResponse {
  postingDate: string
  txnId: string
  entryType: string
  amount: number
  narration: string | null
}

export interface StatementResponse {
  accountId: string
  fromDate: string
  toDate: string
  format: string | null
  lines: StatementLineResponse[]
}

// ── Reference ─────────────────────────────────────────────────────────────────

export interface IfscValidationResponse {
  ifsc: string
  valid: boolean
  message: string
}

// ── Errors ────────────────────────────────────────────────────────────────────

export interface ApiErrorBody {
  code: string
  message: string
  details: Record<string, unknown> | null
  correlationId: string
  timestamp: string
  path: string
}

export interface ApiErrorResponse {
  error: ApiErrorBody
}

// ── Auth ──────────────────────────────────────────────────────────────────────

export type UserRole = 'TELLER' | 'CUSTOMER'
