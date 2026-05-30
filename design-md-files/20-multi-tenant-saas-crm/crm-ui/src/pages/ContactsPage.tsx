import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { contactsApi, type ContactListParams } from '@/api/contacts'
import { LeadStatusBadge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { PageSpinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { extractErrorMessage } from '@/api/client'
import type { LeadStatus } from '@/types'

const LEAD_STATUS_OPTIONS = [
  { label: 'All statuses', value: '' },
  { label: 'New', value: 'NEW' },
  { label: 'Contacted', value: 'CONTACTED' },
  { label: 'Qualified', value: 'QUALIFIED' },
  { label: 'Converted', value: 'CONVERTED' },
  { label: 'Lost', value: 'LOST' },
]

export function ContactsPage() {
  const queryClient = useQueryClient()
  const [params, setParams] = useState<ContactListParams>({ page: 0, pageSize: 20 })
  const [deleteId, setDeleteId] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['contacts', params],
    queryFn: () => contactsApi.list(params),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => contactsApi.delete(id),
    onSuccess: () => {
      toast.success('Contact deleted')
      queryClient.invalidateQueries({ queryKey: ['contacts'] })
      setDeleteId(null)
    },
    onError: (err) => toast.error(extractErrorMessage(err)),
  })

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Contacts</h1>
          <p className="mt-1 text-sm text-gray-500">
            {data ? `${data.meta.totalCount} total` : ''}
          </p>
        </div>
        <Link to="/contacts/new">
          <Button>Add Contact</Button>
        </Link>
      </div>

      {/* Filters */}
      <div className="mb-4 flex gap-3">
        <div className="w-48">
          <Select
            options={LEAD_STATUS_OPTIONS}
            value={params.leadStatus ?? ''}
            onChange={(e) =>
              setParams((p) => ({
                ...p,
                page: 0,
                leadStatus: (e.target.value as LeadStatus) || undefined,
              }))
            }
          />
        </div>
      </div>

      {/* Table */}
      <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
        {isLoading ? (
          <PageSpinner />
        ) : !data || data.data.length === 0 ? (
          <EmptyState
            title="No contacts yet"
            description="Add your first contact to get started."
            action={
              <Link to="/contacts/new">
                <Button>Add Contact</Button>
              </Link>
            }
          />
        ) : (
          <>
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Name', 'Email', 'Company', 'Status', 'Owner', ''].map((h) => (
                    <th
                      key={h}
                      className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {data.data.map((c) => (
                  <tr key={c.contactId} className="hover:bg-gray-50">
                    <td className="whitespace-nowrap px-6 py-4 text-sm font-medium text-gray-900">
                      {c.firstName} {c.lastName ?? ''}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {c.email ?? '—'}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {c.company ?? '—'}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4">
                      <LeadStatusBadge status={c.leadStatus} />
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                      {c.ownerName ?? '—'}
                    </td>
                    <td className="whitespace-nowrap px-6 py-4 text-right text-sm">
                      <div className="flex justify-end gap-2">
                        <Link to={`/contacts/${c.contactId}/edit`}>
                          <Button variant="ghost" size="sm">Edit</Button>
                        </Link>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-red-500 hover:bg-red-50"
                          onClick={() => setDeleteId(c.contactId)}
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

      <ConfirmDialog
        open={deleteId !== null}
        title="Delete contact"
        message="This will permanently delete the contact and cannot be undone."
        onConfirm={() => deleteId && deleteMutation.mutate(deleteId)}
        onCancel={() => setDeleteId(null)}
        loading={deleteMutation.isPending}
      />
    </div>
  )
}
