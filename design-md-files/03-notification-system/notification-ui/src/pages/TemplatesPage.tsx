import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTemplate, useDeprecateTemplate } from '../hooks/useTemplates'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Input } from '../components/ui/Input'
import { Badge } from '../components/ui/Badge'
import { SpinnerPage } from '../components/ui/Spinner'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'
import { extractErrorMessage } from '../utils/errors'
import { formatDateTime } from '../utils/format'
import { CHANNEL_LABELS } from '../constants'

export function TemplatesPage() {
  const navigate = useNavigate()
  const { addToast } = useToast()
  const [lookupId, setLookupId] = useState<string | undefined>(undefined)
  const [inputId, setInputId] = useState('')

  const { data: template, isLoading, isError, error } = useTemplate(lookupId)
  const { mutateAsync: deprecate, isPending: deprecating } = useDeprecateTemplate()

  function handleLookup(e: React.FormEvent) {
    e.preventDefault()
    if (!inputId.trim()) return
    setLookupId(inputId.trim())
  }

  async function handleDeprecate() {
    if (!template) return
    try {
      await deprecate({ templateId: template.templateId, version: template.version })
      addToast('success', `Template v${template.version} deprecated`)
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  const isDeprecated = !!template?.deprecatedAt

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Template Management</h1>
          <p className="mt-1 text-sm text-gray-500">Look up and manage notification templates.</p>
        </div>
        <Button onClick={() => navigate('/templates/create')}>Create Template</Button>
      </div>

      <Card title="Lookup Template">
        <form onSubmit={handleLookup} className="flex gap-3">
          <Input
            placeholder="Template ID (e.g. welcome-email)"
            value={inputId}
            onChange={(e) => setInputId(e.target.value)}
            className="flex-1"
          />
          <Button type="submit" variant="secondary">
            Look up
          </Button>
        </form>
      </Card>

      {lookupId && (
        <>
          {isLoading && <SpinnerPage />}
          {isError && (
            <EmptyState
              title="Template not found"
              description={extractErrorMessage(error)}
            />
          )}
          {template && (
            <Card
              title={`${template.templateId} v${template.version}`}
              action={
                !isDeprecated ? (
                  <Button
                    variant="danger"
                    size="sm"
                    loading={deprecating}
                    onClick={handleDeprecate}
                  >
                    Deprecate
                  </Button>
                ) : (
                  <Badge label="Deprecated" className="bg-red-100 text-red-700" />
                )
              }
            >
              <dl className="space-y-3 text-sm">
                <Row label="Channel" value={<Badge label={CHANNEL_LABELS[template.channel]} className="bg-gray-100 text-gray-700" />} />
                <Row label="Locale" value={template.locale} />
                <Row label="Status" value={
                  <Badge
                    label={template.isActive ? 'Active' : 'Inactive'}
                    className={template.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}
                  />
                } />
                <Row label="Created By" value={template.createdBy} mono />
                <Row label="Created At" value={formatDateTime(template.createdAt)} />
                {template.deprecatedAt && (
                  <Row label="Deprecated At" value={formatDateTime(template.deprecatedAt)} />
                )}
                {template.subject && <Row label="Subject" value={template.subject} />}
                {template.pushTitle && <Row label="Push Title" value={template.pushTitle} />}
              </dl>

              {Object.keys(template.variablesSchema).length > 0 && (
                <div className="mt-4">
                  <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">
                    Variable Schema
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-gray-200 text-left text-xs font-medium text-gray-500 uppercase">
                          <th className="pb-2 pr-4">Variable</th>
                          <th className="pb-2">Description</th>
                        </tr>
                      </thead>
                      <tbody>
                        {Object.entries(template.variablesSchema).map(([k, v]) => (
                          <tr key={k} className="border-b border-gray-100 last:border-0">
                            <td className="py-2 pr-4 font-mono text-gray-700">{k}</td>
                            <td className="py-2 text-gray-600">{v}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </Card>
          )}
        </>
      )}
    </div>
  )
}

function Row({ label, value, mono = false }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-gray-500 shrink-0">{label}</dt>
      <dd className={`text-gray-900 text-right truncate ${mono ? 'font-mono text-xs' : ''}`}>
        {value}
      </dd>
    </div>
  )
}
