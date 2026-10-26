import { useState } from 'react'
import { api } from '../services/api'
import { formatINR } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import type { BalanceVerification } from '../types/api'
import { Button, Card, EmptyState, ErrorMessage, PageHeader, Spinner } from '../components/ui'
import { StatusBadge } from '../components/StatusBadge'
import { Cell, Table } from '../components/tables'

export function AccountsPage() {
  const accounts = useFetch(() => api.accounts())
  // Verification result per account id, filled in when the user clicks "Verify".
  const [results, setResults] = useState<Record<string, BalanceVerification>>({})
  const [error, setError] = useState<unknown>(null)

  async function verify(accountId: string) {
    setError(null)
    try {
      const result = await api.verifyAccount(accountId)
      setResults({ ...results, [accountId]: result })
    } catch (err) {
      setError(err)
    }
  }

  return (
    <div>
      <PageHeader
        title="Accounts"
        actions={
          <Button variant="secondary" onClick={accounts.reload}>
            Refresh
          </Button>
        }
      />

      <div className="space-y-4">
        {error !== null && <ErrorMessage error={error} />}
        <Card>
          {accounts.error && <ErrorMessage error={accounts.error} />}
          {accounts.loading && !accounts.data && <Spinner />}
          {accounts.data && accounts.data.length === 0 && <EmptyState message="No accounts yet." />}
          {accounts.data && accounts.data.length > 0 && (
            <Table headers={['Account', 'Balance', 'Status', 'Ledger check', '']}>
              {accounts.data.map((account) => {
                const result = results[account.id]
                return (
                  <tr key={account.id} className="hover:bg-gray-50">
                    <Cell className="font-medium text-gray-900">{account.displayName}</Cell>
                    <Cell>{formatINR(account.balance)}</Cell>
                    <Cell>
                      <StatusBadge value={account.status} />
                    </Cell>
                    <Cell>{result ? <StatusBadge value={result.status} /> : <span className="text-gray-400">-</span>}</Cell>
                    <Cell className="text-right">
                      <Button variant="secondary" onClick={() => verify(account.id)}>
                        Verify
                      </Button>
                    </Cell>
                  </tr>
                )
              })}
            </Table>
          )}
        </Card>
      </div>
    </div>
  )
}
