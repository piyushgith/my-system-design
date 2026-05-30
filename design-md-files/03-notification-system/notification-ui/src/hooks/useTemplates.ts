import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getTemplate, createTemplate, deprecateTemplate } from '../api/templates'
import type { CreateTemplateRequest } from '../types'

export function useTemplate(templateId: string | undefined) {
  return useQuery({
    queryKey: ['template', templateId],
    queryFn: () => getTemplate(templateId!),
    enabled: !!templateId,
    staleTime: 300_000,
  })
}

export function useCreateTemplate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateTemplateRequest) => createTemplate(body),
    onSuccess: (template) => {
      qc.setQueryData(['template', template.templateId], template)
    },
  })
}

export function useDeprecateTemplate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ templateId, version }: { templateId: string; version: number }) =>
      deprecateTemplate(templateId, version),
    onSuccess: (_data, { templateId }) => {
      qc.invalidateQueries({ queryKey: ['template', templateId] })
    },
  })
}
