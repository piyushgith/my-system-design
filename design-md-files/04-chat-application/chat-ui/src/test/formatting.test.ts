import { describe, it, expect } from 'vitest';
import {
  formatTime,
  formatRelative,
  shortUserId,
  conversationDisplayName,
} from '../utils/formatting';

describe('formatTime', () => {
  it('returns empty string for null', () => {
    expect(formatTime(null)).toBe('');
  });

  it('returns empty string for invalid date', () => {
    expect(formatTime('not-a-date')).toBe('');
  });

  it('formats valid ISO string to HH:MM', () => {
    const result = formatTime('2024-01-15T14:30:00Z');
    expect(result).toMatch(/\d{1,2}:\d{2}/);
  });
});

describe('formatRelative', () => {
  it('returns empty for null', () => {
    expect(formatRelative(null)).toBe('');
  });

  it('returns "just now" for recent dates', () => {
    const recent = new Date(Date.now() - 5000).toISOString();
    expect(formatRelative(recent)).toBe('just now');
  });

  it('returns minutes ago for dates <1h old', () => {
    const past = new Date(Date.now() - 5 * 60 * 1000).toISOString();
    expect(formatRelative(past)).toBe('5m ago');
  });
});

describe('shortUserId', () => {
  it('returns first 8 chars', () => {
    expect(shortUserId('550e8400-e29b-41d4-a716-446655440000')).toBe('550e8400');
  });
});

describe('conversationDisplayName', () => {
  it('returns name when set', () => {
    expect(conversationDisplayName('Team Alpha', 'GROUP', 'abc123')).toBe('Team Alpha');
  });

  it('returns type + id prefix when name is null', () => {
    const result = conversationDisplayName(null, 'DIRECT', '550e8400-e29b-41d4-a716-446655440000');
    expect(result).toBe('DIRECT · 550e8400');
  });
});
