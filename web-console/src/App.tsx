import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import TRTC from 'trtc-sdk-v5'
import {
  endCommandCall,
  fetchDevices,
  getCommandCall,
  startCommandCall,
  type CallSession,
  type DeviceRow,
} from './api'

type TrtcClient = ReturnType<typeof TRTC.create>

function statusClass(status: string): string {
  const s = status.toLowerCase()
  if (s.includes('record') || s.includes('录')) return 'recording'
  if (s.includes('off') || s.includes('离')) return 'offline'
  return 'online'
}

function statusLabel(status: string): string {
  if (!status) return '未知'
  return status
}

export default function App() {
  const [devices, setDevices] = useState<DeviceRow[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [session, setSession] = useState<CallSession | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [trtcReady, setTrtcReady] = useState(false)
  const [clock, setClock] = useState(() =>
    new Date().toLocaleString('zh-CN', { hour12: false }),
  )

  const remoteRef = useRef<HTMLDivElement>(null)
  const trtcRef = useRef<TrtcClient | null>(null)

  useEffect(() => {
    const t = window.setInterval(() => {
      setClock(new Date().toLocaleString('zh-CN', { hour12: false }))
    }, 1000)
    return () => window.clearInterval(t)
  }, [])

  const refreshDevices = useCallback(async () => {
    try {
      const list = await fetchDevices()
      setDevices(list)
      setError('')
      if (!selectedId && list.length > 0) {
        const occupied = list.find((d) => d.inUse) ?? list[0]
        setSelectedId(occupied.id)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [selectedId])

  useEffect(() => {
    void refreshDevices()
    const t = window.setInterval(() => void refreshDevices(), 10_000)
    return () => window.clearInterval(t)
  }, [refreshDevices])

  const leaveTrtc = useCallback(async () => {
    const client = trtcRef.current
    trtcRef.current = null
    setTrtcReady(false)
    if (!client) return
    try {
      await client.stopLocalAudio().catch(() => undefined)
      await client.exitRoom()
      client.destroy()
    } catch {
      try {
        client.destroy()
      } catch {
        /* ignore */
      }
    }
  }, [])

  const enterTrtc = useCallback(
    async (call: CallSession) => {
      await leaveTrtc()
      const p = call.platform
      const client = TRTC.create()
      trtcRef.current = client

      client.on(TRTC.EVENT.REMOTE_VIDEO_AVAILABLE, ({ userId, streamType }) => {
        const view = remoteRef.current
        if (!view) return
        void client.startRemoteVideo({ userId, streamType, view })
      })

      await client.enterRoom({
        sdkAppId: p.sdk_app_id,
        userId: p.user_id,
        userSig: p.user_sig,
        strRoomId: p.room_id,
      })
      await client.startLocalAudio()
      setTrtcReady(true)
    },
    [leaveTrtc],
  )

  useEffect(() => {
    if (!session?.call_id) return
    const id = session.call_id
    const timer = window.setInterval(() => {
      void getCommandCall(id)
        .then((s) => {
          setSession(s)
          if (s.status === 'failed' || s.status === 'ended') {
            void leaveTrtc()
          }
        })
        .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    }, 2000)
    return () => window.clearInterval(timer)
  }, [session?.call_id, leaveTrtc])

  useEffect(() => {
    return () => {
      void leaveTrtc()
    }
  }, [leaveTrtc])

  async function onStart() {
    if (!selectedId || busy) return
    setBusy(true)
    setError('')
    try {
      const call = await startCommandCall(selectedId)
      setSession(call)
      await enterTrtc(call)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSession(null)
      await leaveTrtc()
    } finally {
      setBusy(false)
    }
  }

  async function onEnd() {
    if (!session || busy) return
    setBusy(true)
    setError('')
    try {
      await endCommandCall(session.call_id)
      await leaveTrtc()
      const latest = await getCommandCall(session.call_id).catch(() => null)
      if (latest) setSession(latest)
      else setSession((s) => (s ? { ...s, status: 'ended' } : null))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const inCall =
    session != null && (session.status === 'connecting' || session.status === 'in_call')

  const selected = useMemo(
    () => devices.find((d) => d.id === selectedId) ?? null,
    [devices, selectedId],
  )

  const callStatusClass =
    session?.status === 'in_call'
      ? 'ok'
      : session?.status === 'failed'
        ? 'danger'
        : session?.status === 'connecting'
          ? 'warn'
          : ''

  return (
    <div className="dashboard">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">赢</span>
          <span className="brand-name">赢筑AI · 指挥连线</span>
        </div>
        <span className="source-tag">执法仪 · TRTC</span>
        <span className="clock">{clock}</span>
      </header>

      <main className="main">
        <aside className="panel left-panel">
          <div className="panel-title">
            <span>设备列表</span>
            <button type="button" className="ghost-btn" onClick={() => void refreshDevices()} disabled={busy}>
              刷新
            </button>
          </div>
          <div className="device-scroll">
            {devices.length === 0 ? (
              <div className="empty-hint">暂无设备，请确认后端 dashboard 接口</div>
            ) : (
              devices.map((d) => {
                const sc = statusClass(d.status)
                const selectedCall = inCall && session?.device_id === d.id
                return (
                  <button
                    key={d.id}
                    type="button"
                    className={[
                      'device-card',
                      selectedId === d.id ? 'selected' : '',
                      sc === 'offline' ? 'offline' : '',
                      selectedCall ? 'in-call' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    disabled={inCall && session?.device_id !== d.id}
                    onClick={() => setSelectedId(d.id)}
                  >
                    <div className="card-preview">
                      {sc === 'recording' ? <span className="rec-tag">● REC</span> : null}
                      {sc === 'offline' ? '⚠ 离线' : selectedCall ? '📡 连线中' : '📷 待机画面'}
                    </div>
                    <div className="card-name-row">
                      <span className="dev-name">{d.name || d.id}</span>
                      <span className={`status-dot ${sc}`} />
                    </div>
                    <div className="card-meta">
                      <span>{d.officer || '未绑定'}</span>
                      <span>{d.inUse ? '已占用' : '空闲'}</span>
                    </div>
                  </button>
                )
              })
            )}
          </div>
        </aside>

        <section className="panel center-panel video-shell">
          <div className="panel-title">
            <span>远端画面（设备）</span>
            <span>{selected ? selected.name || selected.id : '未选择设备'}</span>
          </div>
          <div className="video-stage">
            {trtcReady ? <span className="live-chip">● LIVE</span> : null}
            <div id="remote-video" className="remote-video" ref={remoteRef} />
            {!trtcReady ? (
              <div className="video-placeholder">
                <div className="icon">{inCall ? '📡' : '📷'}</div>
                <div>
                  {inCall
                    ? '正在建立指挥连线…'
                    : selected
                      ? '选择右侧操作发起连线'
                      : '请先选择左侧设备'}
                </div>
              </div>
            ) : null}
          </div>
          <div className="param-bar">
            <div className="param">
              <span className="param-label">设备状态</span>
              <span className={`param-value ${statusClass(selected?.status ?? '')}`}>
                {selected ? statusLabel(selected.status) : '—'}
              </span>
            </div>
            <div className="param">
              <span className="param-label">占用</span>
              <span className="param-value">{selected ? (selected.inUse ? '已占用' : '空闲') : '—'}</span>
            </div>
            <div className="param">
              <span className="param-label">TRTC</span>
              <span className={`param-value ${trtcReady ? 'ok' : ''}`}>
                {trtcReady ? '已进房 · 麦开' : '未进房'}
              </span>
            </div>
            <div className="param">
              <span className="param-label">会话</span>
              <span className={`param-value ${callStatusClass}`}>
                {session?.status ?? '空闲'}
              </span>
            </div>
          </div>
        </section>

        <aside className="right-panel">
          <div className="panel control-card">
            <h3>指挥操作</h3>
            <div className="action-stack">
              <button
                type="button"
                className="primary-btn"
                onClick={() => void onStart()}
                disabled={!selectedId || inCall || busy}
              >
                发起连线
              </button>
              <button
                type="button"
                className="danger-btn"
                onClick={() => void onEnd()}
                disabled={!session || busy}
              >
                结束连线
              </button>
            </div>
          </div>

          <div className="panel control-card">
            <h3>会话信息</h3>
            <div className="status-block">
              <div>
                设备：<strong>{selected ? selected.name || selected.id : '—'}</strong>
              </div>
              <div>
                执勤员：<strong>{selected?.officer || '—'}</strong>
              </div>
              <div>
                Call ID：<strong>{session?.call_id || '—'}</strong>
              </div>
              <div>
                Room：<strong>{session?.room_id || '—'}</strong>
              </div>
              {session?.failure_reason ? (
                <div className="fail">失败：{session.failure_reason}</div>
              ) : null}
              {error ? <div className="fail">{error}</div> : null}
            </div>
          </div>
        </aside>
      </main>
    </div>
  )
}
