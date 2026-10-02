/** API client para solicitudes de acceso al rol ANALYST. */

import { apiRequest } from './client'
import type { AnalystAccessRequest, CreateAnalystAccessRequest } from '../types/analyst'

const BASE = '/api/v1/analyst-access-requests'

export async function createAnalystAccessRequest(
  payload: CreateAnalystAccessRequest,
): Promise<AnalystAccessRequest> {
  const { data } = await apiRequest<AnalystAccessRequest>(BASE, { method: 'POST', body: payload })
  return data
}

export async function fetchMyAnalystAccessRequest(): Promise<AnalystAccessRequest | null> {
  const { data } = await apiRequest<AnalystAccessRequest | null>(`${BASE}/my`)
  return data
}
