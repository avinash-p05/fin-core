const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 })
const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium' })

/** Display-only INR formatting (lakh/crore grouping), e.g. ₹1,20,000.00. */
export function formatInr(amount: number | null | undefined) {
  return amount === null || amount === undefined ? '—' : inr.format(amount)
}

export function formatDateTime(iso: string | null | undefined) {
  return iso ? dateTime.format(new Date(iso)) : '—'
}

export function shortId(id: string | null | undefined) {
  return id ? id.slice(0, 8) : '—'
}

export function humanize(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}
