export function generateIdempotencyKey(): string {
  return `${Date.now()}-${crypto.randomUUID()}`
}
