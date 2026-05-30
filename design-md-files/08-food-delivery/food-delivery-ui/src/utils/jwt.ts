import type { UserRole } from '../types/auth'

interface JwtPayload {
  sub: string
  role: string
  iat: number
  exp: number
}

function base64UrlDecode(str: string): string {
  const base64 = str.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
  return atob(padded)
}

export function decodeJwt(token: string): JwtPayload | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const payload = JSON.parse(base64UrlDecode(parts[1]))
    return payload as JwtPayload
  } catch {
    return null
  }
}

export function extractRole(token: string): UserRole {
  const payload = decodeJwt(token)
  return (payload?.role as UserRole) ?? 'CUSTOMER'
}

export function isTokenExpired(token: string): boolean {
  const payload = decodeJwt(token)
  if (!payload) return true
  return Date.now() / 1000 > payload.exp
}
