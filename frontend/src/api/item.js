import axios from 'axios'

const request = axios.create({
  baseURL: '/api/items',
  timeout: 10000
})

export function fetchItems(params) {
  return request.get('', { params })
}

export function searchItems(keyword) {
  return request.get('/search', { params: { keyword } })
}

export function fetchItem(id) {
  return request.get(`/${id}`)
}

export function createItem(data) {
  return request.post('', data)
}

export function updateItem(id, data) {
  return request.put(`/${id}`, data)
}

export function deleteItem(id) {
  return request.delete(`/${id}`)
}
