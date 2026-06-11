import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from '../store/authStore';

const mockUser = {
  userId: '550e8400-e29b-41d4-a716-446655440000',
  username: 'testuser',
  displayName: 'Test User',
  token: 'mock.jwt.token',
};

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  it('starts with no user', () => {
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('setUser stores user', () => {
    useAuthStore.getState().setUser(mockUser);
    expect(useAuthStore.getState().user).toEqual(mockUser);
  });

  it('clearUser removes user', () => {
    useAuthStore.getState().setUser(mockUser);
    useAuthStore.getState().clearUser();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('isAuthenticated returns true when user set', () => {
    useAuthStore.getState().setUser(mockUser);
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
  });

  it('isAuthenticated returns false when no user', () => {
    expect(useAuthStore.getState().isAuthenticated()).toBe(false);
  });
});
