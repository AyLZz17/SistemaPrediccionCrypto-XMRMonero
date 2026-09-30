import clsx from 'clsx'
import type { ReactNode } from 'react'

export interface Column<T> {
  key: string
  header: ReactNode
  /** Right-aligns and switches to the monospace face (numbers, ids, dates). */
  numeric?: boolean
  render?: (row: T, index: number) => ReactNode
  width?: string
}

export interface DataTableProps<T> {
  /** Accessible caption; also used as the visible title when `showCaption`. */
  caption: string
  columns: Array<Column<T>>
  rows: T[]
  rowKey: (row: T, index: number) => string
  empty?: ReactNode
  className?: string
  dense?: boolean
  /** Highlights the champion / selected row. */
  isRowActive?: (row: T) => boolean
}

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  empty = 'Sin registros.',
  className,
  dense = false,
  isRowActive,
}: DataTableProps<T>) {
  const cellPad = dense ? 'px-3 py-1.5' : 'px-4 py-2.5'

  return (
    <div className={clsx('w-full overflow-x-auto rounded-md border border-hairline-subtle', className)}>
      <table className="w-full border-collapse text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-hairline bg-surface-inset">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                style={column.width ? { width: column.width } : undefined}
                className={clsx(
                  'label-caps whitespace-nowrap font-medium',
                  cellPad,
                  column.numeric && 'text-right',
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-4 py-10 text-center text-sm text-ink-muted">
                {empty}
              </td>
            </tr>
          ) : (
            rows.map((row, index) => {
              const active = isRowActive?.(row) ?? false
              return (
                <tr
                  key={rowKey(row, index)}
                  className={clsx(
                    'border-b border-hairline-subtle transition-colors duration-fast last:border-b-0',
                    'hover:bg-surface-2',
                    active && 'bg-accent-cyan-soft',
                  )}
                >
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={clsx(
                        'align-middle text-ink-secondary',
                        cellPad,
                        column.numeric && 'text-right font-mono tabular-nums text-ink',
                      )}
                    >
                      {column.render
                        ? column.render(row, index)
                        : String((row as Record<string, unknown>)[column.key] ?? '')}
                    </td>
                  ))}
                </tr>
              )
            })
          )}
        </tbody>
      </table>
    </div>
  )
}

export interface PaginationProps {
  page: number
  totalPages: number
  total: number
  size: number
  onPageChange: (page: number) => void
  disabled?: boolean
}

/** Zero-based `page` on the wire, 1-based display for humans. */
export function Pagination({ page, totalPages, total, size, onPageChange, disabled }: PaginationProps) {
  const current = Math.min(page + 1, Math.max(totalPages, 1))
  const from = total === 0 ? 0 : page * size + 1
  const to = Math.min(total, page * size + size)

  return (
    <nav
      aria-label="Paginacion"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-hairline-subtle pt-3 text-xs text-ink-muted"
    >
      <p className="font-mono tabular-nums">
        {from}-{to} de {total} registro{total === 1 ? '' : 's'}
      </p>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => onPageChange(page - 1)}
          disabled={disabled || page <= 0}
          className="rounded-md border border-hairline bg-surface-2 px-3 py-1.5 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
        >
          Anterior
        </button>
        <span className="font-mono tabular-nums text-ink-secondary">
          Pagina {current} / {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(page + 1)}
          disabled={disabled || totalPages === 0 || page >= totalPages - 1}
          className="rounded-md border border-hairline bg-surface-2 px-3 py-1.5 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
        >
          Siguiente
        </button>
      </div>
    </nav>
  )
}
