import { useState, useEffect, useRef, useCallback } from 'react'
import axios from 'axios'

const API = '/api'

function formatSize(bytes) {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
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
    setProgress(0)
    setLoaded(0)
    setFileSize(file.size)
    setCurrentFile(file.name)
    setQueueInfo({ current, total })

    const formData = new FormData()
    formData.append('file', file)

    await axios.post(`${API}/upload`, formData, {
      onUploadProgress: (e) => {
        setLoaded(e.loaded)
        // e.total pode ser 0 em browsers mobile (Safari/iOS) quando o
        // Content-Length não é reportado — usa file.size como fallback
        const knownTotal = e.total || file.size
        if (knownTotal) setProgress(Math.round((e.loaded * 100) / knownTotal))
      },
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
