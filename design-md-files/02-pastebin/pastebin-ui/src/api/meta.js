import client from './client'

export const getLanguages = () => client.get('/meta/languages')
