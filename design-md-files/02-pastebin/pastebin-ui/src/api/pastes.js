import client from './client'

export const createPaste = (data, idempotencyKey) => {
  const headers = {}
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  return client.post('/pastes', data, { headers })
}

export const getPaste = (key, password) => {
  const headers = {}
  if (password) headers['X-Paste-Password'] = password
  return client.get(`/pastes/${key}`, { headers })
}

export const deletePaste = (key) =>
  client.delete(`/pastes/${key}`)

export const listMyPastes = (params) =>
  client.get('/users/me/pastes', { params })
