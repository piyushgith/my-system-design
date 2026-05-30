import client from './client'
import axios from 'axios'

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

export const getRawContent = async (key, password) => {
  const headers = {}
  const token = localStorage.getItem('pb_token')
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (password) headers['X-Paste-Password'] = password
  const res = await axios.get(`/raw/${key}`, { headers })
  return res.data
}
