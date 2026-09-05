import { useEffect, useState } from 'react'

// 서버 에러 응답에서 사람이 읽을 메시지를 뽑아낸다
async function toErrorMessage(res) {
  try {
    const body = await res.json()
    return body.message || `요청 실패 (${res.status})`
  } catch {
    return `요청 실패 (${res.status})`
  }
}

export default function App() {
  const [urls, setUrls] = useState([])
  const [clicksByCode, setClicksByCode] = useState({})
  const [originalUrl, setOriginalUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  // 목록 + 클릭수를 함께 불러온다
  async function loadAll() {
    setLoading(true)
    setError('')
    try {
      const [listRes, statsRes] = await Promise.all([
        fetch('/api/urls'),
        fetch('/api/stats/top?limit=20'),
      ])
      if (!listRes.ok) throw new Error(await toErrorMessage(listRes))
      const list = await listRes.json()
      setUrls(list)

      if (statsRes.ok) {
        const stats = await statsRes.json()
        const map = {}
        for (const s of stats) map[s.shortCode] = s.clicks
        setClicksByCode(map)
      } else {
        setClicksByCode({})
      }
    } catch (e) {
      setError(e.message || '목록을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  // 새 단축 URL 생성
  async function handleCreate(e) {
    e.preventDefault()
    if (!originalUrl.trim()) return
    setLoading(true)
    setError('')
    try {
      const res = await fetch('/api/urls', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ originalUrl: originalUrl.trim() }),
      })
      if (!res.ok) throw new Error(await toErrorMessage(res))
      setOriginalUrl('')
      await loadAll()
    } catch (e) {
      setError(e.message || 'URL 생성에 실패했습니다.')
      setLoading(false)
    }
  }

  // 단축 URL 삭제
  async function handleDelete(shortCode) {
    setLoading(true)
    setError('')
    try {
      const res = await fetch(`/api/urls/${shortCode}`, { method: 'DELETE' })
      if (!res.ok && res.status !== 204) throw new Error(await toErrorMessage(res))
      await loadAll()
    } catch (e) {
      setError(e.message || '삭제에 실패했습니다.')
      setLoading(false)
    }
  }

  return (
    <div className="page">
      <h1>URL 단축기</h1>

      {error && <div className="error-banner">{error}</div>}

      <div className="card">
        <form onSubmit={handleCreate} className="create-form">
          <input
            type="text"
            placeholder="단축할 URL을 입력하세요 (예: https://example.com)"
            value={originalUrl}
            onChange={(e) => setOriginalUrl(e.target.value)}
            disabled={loading}
          />
          <button type="submit" disabled={loading}>
            생성
          </button>
        </form>
      </div>

      <div className="card">
        <div className="list-header">
          <h2>단축 URL 목록</h2>
          <button onClick={loadAll} disabled={loading}>
            새로고침
          </button>
        </div>

        {loading && <p className="loading">불러오는 중...</p>}

        <table>
          <thead>
            <tr>
              <th>단축 링크</th>
              <th>원본 URL</th>
              <th>클릭수</th>
              <th>만료일</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {urls.map((u) => (
              <tr key={u.shortCode}>
                <td>
                  <a href={`/${u.shortCode}`} target="_blank" rel="noreferrer">
                    /{u.shortCode}
                  </a>
                </td>
                <td className="original-url" title={u.originalUrl}>
                  {u.originalUrl}
                </td>
                <td>{clicksByCode[u.shortCode] ?? 0}</td>
                <td>{new Date(u.expiresAt).toLocaleString()}</td>
                <td>
                  <button onClick={() => handleDelete(u.shortCode)} disabled={loading}>
                    삭제
                  </button>
                </td>
              </tr>
            ))}
            {urls.length === 0 && !loading && (
              <tr>
                <td colSpan={5} className="empty">
                  아직 생성된 단축 URL이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
