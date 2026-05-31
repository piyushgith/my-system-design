import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createShortUrl } from '@/api/urls'
import apiClient from '@/api/client'

vi.mock('@/api/client', () => ({
  default: {
    post: vi.fn(),
  },
}))

const mockPost = vi.mocked(apiClient.post)

describe('createShortUrl', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sends only longUrl when alias and ttl are absent', async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        shortUrl: 'http://localhost:8080/abc1234',
        shortCode: 'abc1234',
        longUrl: 'https://example.com',
        createdAt: '2024-01-01T00:00:00Z',
        expiresAt: null,
      },
    })

    await createShortUrl({ longUrl: 'https://example.com' })

    expect(mockPost).toHaveBeenCalledWith('/v1/urls', { longUrl: 'https://example.com' })
  })

  it('includes alias when provided', async () => {
    mockPost.mockResolvedValueOnce({ data: {} })
    await createShortUrl({ longUrl: 'https://example.com', alias: 'my-link' })
    expect(mockPost).toHaveBeenCalledWith('/v1/urls', {
      longUrl: 'https://example.com',
      alias: 'my-link',
    })
  })

  it('includes ttl when provided', async () => {
    mockPost.mockResolvedValueOnce({ data: {} })
    await createShortUrl({ longUrl: 'https://example.com', ttl: 86400 })
    expect(mockPost).toHaveBeenCalledWith('/v1/urls', {
      longUrl: 'https://example.com',
      ttl: 86400,
    })
  })

  it('omits alias when empty string', async () => {
    mockPost.mockResolvedValueOnce({ data: {} })
    await createShortUrl({ longUrl: 'https://example.com', alias: '' })
    const call = mockPost.mock.calls[0][1] as Record<string, unknown>
    expect(call).not.toHaveProperty('alias')
  })
})
