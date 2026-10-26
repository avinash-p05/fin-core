import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { ApiError } from '../services/api'

export function PageHeader({ title, actions }: { title: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
      <h1 className="text-2xl font-semibold text-gray-900">{title}</h1>
      {actions && <div className="flex gap-2">{actions}</div>}
    </div>
  )
}

export function Card({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
      {title && (
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3">
          <h2 className="font-medium text-gray-900">{title}</h2>
          {actions}
        </div>
      )}
      <div className="p-5">{children}</div>
    </div>
  )
}

type ButtonVariant = 'primary' | 'secondary' | 'danger'

const buttonStyles: Record<ButtonVariant, string> = {
  primary: 'bg-blue-600 text-white hover:bg-blue-700',
  secondary: 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
  danger: 'bg-red-600 text-white hover:bg-red-700',
}

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button
      className={`rounded-md px-4 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${buttonStyles[variant]} ${className}`}
      {...props}
    />
  )
}

export function Label({ text, children }: { text: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-gray-700">{text}</span>
      {children}
    </label>
  )
}

const controlClass =
  'rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'

/** Full-width input or select, used in forms. */
export const inputClass = `w-full ${controlClass}`

/** Auto-width select, used for filters above tables. */
export const filterClass = controlClass

export function Spinner() {
  return (
    <div className="flex justify-center py-8">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-gray-300 border-t-blue-600" />
    </div>
  )
}

export function ErrorMessage({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : 'Something went wrong'
  const status = error instanceof ApiError ? error.status : null
  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {status && <strong className="mr-1">Error {status}:</strong>}
      {message}
    </div>
  )
}

export function EmptyState({ message }: { message: string }) {
  return <p className="py-8 text-center text-sm text-gray-500">{message}</p>
}

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div className="mt-4 flex items-center justify-end gap-3 text-sm text-gray-600">
      <Button variant="secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </Button>
      <span>
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </Button>
    </div>
  )
}
