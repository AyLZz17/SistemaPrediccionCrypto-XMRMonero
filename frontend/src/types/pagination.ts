/** Paginated envelope returned by every list endpoint of the API contract. */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export const DEFAULT_PAGE_SIZE = 20

export function emptyPage<T>(size = DEFAULT_PAGE_SIZE): Page<T> {
  return { items: [], page: 0, size, total: 0, totalPages: 0 }
}

export interface PageParams {
  page?: number
  size?: number
}

export function isPage<T>(value: unknown): value is Page<T> {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return Array.isArray(candidate.items)
}
