export interface TtlOption {
  label: string
  value: number | undefined // seconds; undefined = no expiry
}

export const TTL_OPTIONS: TtlOption[] = [
  { label: 'Never expires', value: undefined },
  { label: '1 hour', value: 3_600 },
  { label: '24 hours', value: 86_400 },
  { label: '7 days', value: 604_800 },
  { label: '30 days', value: 2_592_000 },
]
