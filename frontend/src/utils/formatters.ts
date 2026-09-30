export const formatCurrency = (value: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
  }).format(value)
}

export const formatPercent = (value: number): string => {
  return `${value.toFixed(2)}%`
}

export const formatDate = (date: string): string => {
  return new Date(date).toLocaleDateString('es-ES')
}
