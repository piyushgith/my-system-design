import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getPreferences, updatePreferences } from '../api/preferences'
import type { UpdatePreferencesRequest } from '../types'

export function usePreferences(userId: string | undefined) {
  return useQuery({
    queryKey: ['preferences', userId],
    queryFn: () => getPreferences(userId!),
    enabled: !!userId,
    staleTime: 60_000,
  })
}

export function useUpdatePreferences(userId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdatePreferencesRequest) => updatePreferences(userId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['preferences', userId] })
    },
  })
}
