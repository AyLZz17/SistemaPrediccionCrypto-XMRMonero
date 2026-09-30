export interface Dataset {
  id: number
  name: string
  domain: string
  source: string
  symbol?: string
  records_count: number
  start_date?: string
  end_date?: string
  status: string
  created_at: string
}

export interface DatasetListResponse {
  items: Dataset[]
  total: number
  page: number
  per_page: number
}
