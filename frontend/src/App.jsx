import { useState, useEffect, useRef, useCallback } from 'react'
import axios from 'axios'

// Em produção (build nginx) usa proxy relativo; em dev aponta direto para o
// backend para evitar que o Vite bufferize arquivos grandes na memória.
const API = import.meta.env.PROD
  ? '/api'
  : `http://${window.location.hostname}:8000`

const CHUNK_SIZE = 2 * 1024 * 1024 // 2 MB

const sendLog = (data) => {
  axios.post(`${API}/debug/log`, { ts: new Date().toISOString(), ...data }).catch(() => {})
}

function verifyReadable(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve()
    reader.onerror = () => reject(new Error(
      `Arquivo ilegível: "${file.name}" não pôde ser lido pelo browser. ` +
      'Verifique se o arquivo não é esparso (fallocate/truncate) e se o app tem permissão de leitura.'
    ))
    reader.readAsArrayBuffer(file.slice(0, 1))
  })
}

function randomUUID() {
  return ([1e7] + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, c =>
    (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16)
  )
}

function formatSize(bytes) {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

function formatTime(seconds) {
  if (!seconds || !isFinite(seconds) || seconds < 0) return '--'
  if (seconds < 60) return `${Math.round(seconds)}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

function formatDate(iso) {
  const d = new Date(iso)
  return d.toLocaleDateString('pt-BR') + ' ' + d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
}

function fileIcon(name) {
  const ext = name.split('.').pop().toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'heic'].includes(ext)) return '🖼'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return '🎬'
  if (['mp3', 'wav', 'flac', 'aac', 'm4a'].includes(ext)) return '🎵'
  if (['pdf'].includes(ext)) return '📕'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return '🗜'
  if (['doc', 'docx'].includes(ext)) return '📝'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return '📊'
  return '📄'
}

export default function App() {
  const [files, setFiles] = useState([])
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState(0)
  const [loaded, setLoaded] = useState(0)
  const [fileSize, setFileSize] = useState(0)
  const [speed, setSpeed] = useState(0)
  const [eta, setEta] = useState(null)
  const [currentFile, setCurrentFile] = useState('')
  const [queueInfo, setQueueInfo] = useState({ current: 0, total: 0 })
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState(null)
  const inputRef = useRef()

  const fetchFiles = useCallback(async () => {
    try {
      const { data } = await axios.get(`${API}/files`)
      setFiles(data)
    } catch {
      setError('Falha ao carregar arquivos.')
    }
  }, [])

  useEffect(() => {
    fetchFiles()
  }, [fetchFiles])

  const uploadOne = async (file, current, total) => {
    await verifyReadable(file)

    setProgress(0)
    setLoaded(0)
    setFileSize(file.size)
    setSpeed(0)
    setEta(null)
    setCurrentFile(file.name)
    setQueueInfo({ current, total })

    const fileId = randomUUID()
    const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE))
    const uploadStart = Date.now()

    sendLog({
      event: 'upload_start',
      session: fileId,
      file: file.name,
      file_size: file.size,
      file_type: file.type || 'unknown',
      total_chunks: totalChunks,
      user_agent: navigator.userAgent,
    })

    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE
      const chunk = file.slice(start, Math.min(start + CHUNK_SIZE, file.size))
      const chunkStart = Date.now()

      const formData = new FormData()
      formData.append('file', chunk, file.name)
      formData.append('file_id', fileId)
      formData.append('chunk_index', String(i))
      formData.append('total_chunks', String(totalChunks))

      const MAX_RETRIES = 3
      let attempt = 0
      while (true) {
        try {
          await axios.post(`${API}/upload`, formData, {
            onUploadProgress: (e) => {
              const sent = i * CHUNK_SIZE + (e.loaded || 0)
              const clamped = Math.min(sent, file.size)
              setLoaded(clamped)
              if (file.size > 0) setProgress(Math.round((clamped * 100) / file.size))
              const elapsed = (Date.now() - uploadStart) / 1000
              if (elapsed > 0.5 && clamped > 0) {
                const currentSpeed = clamped / elapsed
                setSpeed(currentSpeed)
                setEta((file.size - clamped) / currentSpeed)
              }
            },
          })
          break
        } catch (err) {
          attempt++
          sendLog({
            event: 'chunk_error',
            session: fileId,
            chunk_index: i,
            total_chunks: totalChunks,
            duration_ms: Date.now() - chunkStart,
            attempt,
            error: err.message,
            error_code: err.code,
            http_status: err.response?.status ?? null,
          })
          if (attempt >= MAX_RETRIES) throw err
          await new Promise((r) => setTimeout(r, 1000 * attempt))
        }
      }

      sendLog({
        event: 'chunk_done',
        session: fileId,
        chunk_index: i,
        total_chunks: totalChunks,
        duration_ms: Date.now() - chunkStart,
      })
    }

    sendLog({
      event: 'upload_done',
      session: fileId,
      file: file.name,
      file_size: file.size,
      total_chunks: totalChunks,
      duration_ms: Date.now() - uploadStart,
    })
  }

  const handleFiles = async (fileList) => {
    const list = Array.from(fileList)
    if (list.length === 0) return

    setUploading(true)
    setError(null)

    try {
      for (let i = 0; i < list.length; i++) {
        await uploadOne(list[i], i + 1, list.length)
      }
      await fetchFiles()
    } catch (e) {
      setError(`Erro no upload: ${e.message}`)
    } finally {
      setUploading(false)
      setCurrentFile('')
      setProgress(0)
      setLoaded(0)
      setFileSize(0)
      setSpeed(0)
      setEta(null)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  const handleDrop = (e) => {
    e.preventDefault()
    setDragOver(false)
    if (!uploading) handleFiles(e.dataTransfer.files)
  }

  const handleDelete = async (filename) => {
    if (!window.confirm(`Excluir "${filename}"?`)) return
    try {
      await axios.delete(`${API}/files/${encodeURIComponent(filename)}`)
      setFiles((f) => f.filter((x) => x.name !== filename))
    } catch {
      setError('Falha ao excluir arquivo.')
    }
  }

  return (
    <div className="app">
      <header>
        <h1>File Storage</h1>
        <p className="subtitle">{files.length} arquivo{files.length !== 1 ? 's' : ''}</p>
      </header>

      <main>
        <div
          className={`upload-zone${dragOver ? ' drag-over' : ''}${uploading ? ' is-uploading' : ''}`}
          onClick={() => !uploading && inputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); if (!uploading) setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
        >
          <input
            ref={inputRef}
            type="file"
            multiple
            style={{ display: 'none' }}
            onChange={(e) => handleFiles(e.target.files)}
          />

          {uploading ? (
            <div className="upload-progress">
              <div className="progress-filename">
                {queueInfo.total > 1 && (
                  <span className="queue-badge">{queueInfo.current}/{queueInfo.total}</span>
                )}
                <span className="progress-name">{currentFile}</span>
              </div>
              <div className="progress-bar">
                <div
                  className={`progress-fill${progress === 0 ? ' progress-indeterminate' : ''}`}
                  style={progress > 0 ? { width: `${progress}%` } : {}}
                />
              </div>
              <div className="progress-pct">
                {progress > 0 ? `${progress}%` : 'Enviando...'}
                {loaded > 0 && (
                  <span className="progress-bytes">
                    {' '}· {formatSize(loaded)}{fileSize > 0 ? ` / ${formatSize(fileSize)}` : ''}
                  </span>
                )}
              </div>
              {speed > 0 && (
                <div className="progress-stats">
                  <span>{formatSize(speed)}/s</span>
                  <span className="progress-stats-sep">·</span>
                  <span>{formatTime(eta)} restantes</span>
                </div>
              )}
            </div>
          ) : (
            <div className="upload-idle">
              <div className="upload-arrow">↑</div>
              <div className="upload-label">Toque para selecionar arquivos</div>
              <div className="upload-sub">ou arraste e solte aqui</div>
            </div>
          )}
        </div>

        {error && (
          <div className="error-banner" onClick={() => setError(null)}>
            <span>{error}</span>
            <span className="error-close">✕</span>
          </div>
        )}

        <div className="file-list">
          {files.length === 0 ? (
            <div className="empty-state">Nenhum arquivo ainda. Faça um upload!</div>
          ) : (
            files.map((file) => (
              <div key={file.name} className="file-card">
                <div className="file-icon" aria-hidden="true">{fileIcon(file.name)}</div>
                <div className="file-info">
                  <div className="file-name" title={file.name}>{file.name}</div>
                  <div className="file-meta">{formatSize(file.size)} · {formatDate(file.modified)}</div>
                </div>
                <div className="file-actions">
                  <a
                    className="action-btn download-btn"
                    href={`${API}/files/${encodeURIComponent(file.name)}`}
                    download={file.name}
                    title="Baixar"
                    onClick={(e) => e.stopPropagation()}
                  >
                    ↓
                  </a>
                  <button
                    className="action-btn delete-btn"
                    onClick={() => handleDelete(file.name)}
                    title="Excluir"
                  >
                    ✕
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </main>
    </div>
  )
}
