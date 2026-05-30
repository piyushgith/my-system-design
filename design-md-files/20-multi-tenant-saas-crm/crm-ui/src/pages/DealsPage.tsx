import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { dealsApi, type DealListParams } from '@/api/deals'
import { pipelinesApi } from '@/api/pipelines'
import { DealStatusBadge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { PageSpinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Modal } from '@/components/ui/Modal'
import { extractErrorMessage } from '@/api/client'
import type { DealStatus, Stage } from '@/types'

const STATUS_OPTIONS = [
  { label: 'All statuses', value: '' },
  { label: 'Open', value: 'OPEN' },
  { label: 'Won', value: 'WON' },
  { label: 'Lost', value: 'LOST' },
]

function formatValue(value: number | null, currency: string | null) {
  if (value == null) return '—'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency ?? 'USD',
    maximumFractionDigits: 0,
  }).format(value)
}

export function DealsPage() {
  const queryClient = useQueryClient()
  const [params, setParams] = useState<DealListParams>({ page: 0, pageSize: 20 })
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [stageModalDealId, setStageModalDealId] = useState<string | null>(null)
  const [selectedPipelineId, setSelectedPipelineId] = useState<string>('')

  const { data, isLoading } = useQuery({
    queryKey: ['deals', params],
    queryFn: () => dealsApi.list(params),
  })

  const { data: pipelines } = useQuery({
    queryKey: ['pipelines'],
    queryFn: pipelinesApi.list,
  })

  const { data: stages } = useQuery({
    queryKey: ['stages', selectedPipelineId],
    queryFn: () => pipelinesApi.listStages(selectedPipelineId),
    enabled: !!selectedPipelineId,
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => dealsApi.delete(id),
    onSuccess: () => {
      toast.success('Deal deleted')
      queryClient.invalidateQueries({ queryKey: ['deals'] })
      setDeleteId(null)
    },
    onError: (err) => toast.error(extractErrorMessage(err)),
  })

  const stageMutation = useMutation({
    mutationFn: ({ id, stageId }: { id: string; stageId: string }) =>
      dealsApi.moveStage(id, { stageId }),
    onSuccess: () => {
      toast.success('Stage updated')
      queryClient.invalidateQueries({ queryKey: ['deals'] })
      setStageModalDealId(null)
    },
    onError: (err) => toast.error(extractErrorMessage(err)),
  })

  const stageModalDeal = data?.data.find((d) => d.dealId === stageModalDealId)

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Deals</h1>
          <p className="mt-1 text-sm text-gray-500">
            {data ? `${data.meta.totalCount} total` : ''}
          </p>
        </div>
        <Link to="/deals/new">
          <Button>Add Deal</Button>
        </Link>
      </div>

      {/* Filters */}
      <div className="mb-4 flex gap-3">
        <div className="w-48">
          <Select
            options={STATUS_OPTIONS}
            value={params.status ?? ''}
            onChange={(e) =>
              setParams((p) => ({
                ...p,
                page: 0,
                status: (e.target.value as DealStatus) || undefined,
              }))
            }
          />
        </div>
        {pipelines && (
          <div className="w-48">
            <Select
              options={[
                { label: 'All pipelines', value: '' },
                ...pipelines.map((p) => ({ label: p.name, value: p.pipelineId })),
              ]}
              value={params.pipelineId ?? ''}
              onChange={(e) =>
                setParams((p) => ({
                  ...p,
                  page: 0,
                  pipelineId: e.target.value || undefined,
                }))
              }
            />
          </div>
        )}
      </div>

      {/* Table */}
      <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
        {isLoading ? (
          <PageSpinner />
        ) : !data || data.data.length === 0 ? (
          <EmptyState
            title="No deals yet"
            description="Add your first deal to get started."
            action={
              <Link to="/deals/new">
                <Button>Add Deal</Button>
              </Link>
            }
          />
        ) : (
          <>
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Title', 'Value', 'Pipeline / Stage', 'Status', 'Owner', 'Close Date', ''].map(
                    (h) => (
                      <th
                        key={h}
                        className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                      >
                        {h}
                      </th>
                    )
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {data.data.map((deal) => (
                  <tr key={deal.dealId} className="hover:bg-gray-50">
                    <td className="whitespace-nowrap px-6 py-4 text-sm font-medium text-gray-900">
                      {deal.title}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {formatValue(deal.value, deal.currency)}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      <div>{deal.pipelineName}</div>
                      <button
                        onClick={() => {
                          setStageModalDealId(deal.dealId)
                          setSelectedPipelineId(deal.pipelineId)
                        }}
                        className="text-xs text-blue-600 hover:underline"
                      >
                        {deal.stageName} ({deal.stageProbability}%)
                      </button>
                    </td>
                    <td className="whitespace-nowrap px-6 py-4">
                      <DealStatusBadge status={deal.status} />
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {deal.ownerName}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {deal.expectedCloseDate ?? '—'}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-right text-sm">
                      <div className="flex justify-end gap-2">
                        <Link to={`/deals/${deal.dealId}/edit`}>
                          <Button variant="ghost" size="sm">Edit</Button>
                        </Link>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-red-500 hover:bg-red-50"
                          onClick={() => setDeleteId(deal.dealId)}
                        >
                          Delete
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <Pagination
              meta={data.meta}
              onPageChange={(page) => setParams((p) => ({ ...p, page }))}
            />
          </>
        )}
      </div>

      {/* Stage transition modal */}
      <Modal
        open={stageModalDealId !== null}
        onClose={() => setStageModalDealId(null)}
        title={`Move stage — ${stageModalDeal?.title ?? ''}`}
        size="sm"
      >
        <p className="mb-3 text-sm text-gray-500">
          Current: <strong>{stageModalDeal?.stageName}</strong>
        </p>
        {stages ? (
          <div className="space-y-2">
            {stages.map((s: Stage) => (
              <button
                key={s.stageId}
                disabled={s.stageId === stageModalDeal?.stageId || stageMutation.isPending}
                onClick={() =>
                  stageModalDealId &&
                  stageMutation.mutate({ id: stageModalDealId, stageId: s.stageId })
                }
                className="flex w-full items-center justify-between rounded-md border border-gray-200 px-4 py-2 text-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <span>{s.name}</span>
                <span className="text-gray-400">{s.probability}%</span>
              </button>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-500">Loading stages…</p>
        )}
      </Modal>

      <ConfirmDialog
        open={deleteId !== null}
        title="Delete deal"
        message="This will permanently delete the deal and cannot be undone."
        onConfirm={() => deleteId && deleteMutation.mutate(deleteId)}
        onCancel={() => setDeleteId(null)}
        loading={deleteMutation.isPending}
      />
    </div>
  )
}
