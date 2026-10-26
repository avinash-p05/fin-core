import { useEffect, useState } from 'react'

/**
 * Loads data when the component mounts and again whenever `deps` change.
 * Returns the data, an error (if any), a loading flag and a `reload` function.
 */
export function useFetch<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    // Ignore the result if the component re-rendered with new deps before the request finished.
    let ignore = false
    setLoading(true)
    setError(null)

    load()
      .then((result) => {
        if (!ignore) setData(result)
      })
      .catch((err: Error) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })

    return () => {
      ignore = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version])

  const reload = () => setVersion((v) => v + 1)

  return { data, error, loading, reload }
}
