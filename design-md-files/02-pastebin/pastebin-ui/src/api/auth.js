import client from './client'

export const register = (email, password, displayName) =>
  client.post('/auth/register', { email, password, displayName })

export const login = (email, password) =>
  client.post('/auth/login', { email, password })
