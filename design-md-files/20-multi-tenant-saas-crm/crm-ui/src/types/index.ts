// ─── Enums ────────────────────────────────────────────────────────────────────

export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUALIFIED' | 'CONVERTED' | 'LOST'
export type DealStatus = 'OPEN' | 'WON' | 'LOST'
export type UserRole = 'ADMIN' | 'MANAGER' | 'SALES_REP' | 'VIEWER'

// ─── Auth ─────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  email: string
  password: string
  fullName?: string
}

export interface AuthResponse {
  token: string
  userId: string
  email: string
  fullName: string
  role: UserRole
}

// ─── Contacts ─────────────────────────────────────────────────────────────────

export interface ContactRequest {
  firstName: string
  lastName?: string
  email?: string
  phone?: string
  company?: string
  notes?: string
  leadStatus?: LeadStatus
  ownerId?: string
}

export interface ContactResponse {
  contactId: string
  firstName: string
  lastName: string | null
  email: string | null
  phone: string | null
  company: string | null
  notes: string | null
  leadStatus: LeadStatus
  ownerId: string | null
  ownerName: string | null
  createdAt: string
  updatedAt: string
}

// ─── Deals ────────────────────────────────────────────────────────────────────

export interface DealRequest {
  title: string
  value?: number
  currency?: string
  pipelineId: string
  stageId: string
  contactId?: string
  ownerId: string
  expectedCloseDate?: string
  status?: DealStatus
}

export interface DealResponse {
  dealId: string
  title: string
  value: number | null
  currency: string | null
  pipelineId: string
  pipelineName: string
  stageId: string
  stageName: string
  stageProbability: number
  contactId: string | null
  ownerId: string
  ownerName: string
  expectedCloseDate: string | null
  status: DealStatus
  createdAt: string
  updatedAt: string
  closedAt: string | null
}

export interface StageTransitionRequest {
  stageId: string
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

export interface DashboardResponse {
  totalContacts: number
  openLeads: number
  openDeals: number
  wonDeals: number
  lostDeals: number
  openPipelineValue: number
}

// ─── Pipelines ────────────────────────────────────────────────────────────────

export interface Pipeline {
  pipelineId: string
  name: string
  defaultPipeline: boolean
  createdAt: string
}

export interface Stage {
  stageId: string
  name: string
  stageOrder: number
  probability: number
  createdAt: string
}

// ─── Pagination ───────────────────────────────────────────────────────────────

export interface PageMeta {
  totalCount: number
  page: number
  pageSize: number
  totalPages: number
}

export interface PageResponse<T> {
  data: T[]
  meta: PageMeta
}

// ─── Errors ───────────────────────────────────────────────────────────────────

export interface ApiError {
  error: {
    code: string
    message: string
    details: string[]
    correlationId: string
  }
}
