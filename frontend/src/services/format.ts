const inrFormatter = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })
const dateFormatter = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' })

/** 120000 -> "₹1,20,000.00" */
export function formatINR(amount: number | null | undefined) {
  return amount == null ? '-' : inrFormatter.format(amount)
}

export function formatDate(iso: string | null | undefined) {
  return iso ? dateFormatter.format(new Date(iso)) : '-'
}

/** "PENDING_REVIEW" -> "Pending review" */
export function toLabel(value: string) {
  const text = value.replace(/_/g, ' ').toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** First 8 characters of a UUID, enough to recognise it in a table. */
export function shortId(id: string | null | undefined) {
  return id ? id.slice(0, 8) : '-'
}
