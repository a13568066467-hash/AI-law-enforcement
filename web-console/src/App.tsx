import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import TRTC from 'trtc-sdk-v5'
import {
  endCommandCall,
  endWatch,
  fetchDevices,
  getCommandCall,
  startCommandCall,
  startWatch,
  upgradeWatchToCall,
  watchHeartbeat,
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

function isWatchSession(s: CallSession | null): boolean {
  return s?.kind === 'watch' && (s.status === 'connecting' || s.status === 'watching')
}

function isCallSession(s: CallSession | null): boolean {
  return (
    s != null &&
    s.kind === 'call' &&
    (s.status === 'connecting' || s.status === 'in_call')
  )
}

export default function App() {
  const [devices, setDevices] = useState<DeviceRow[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [session, setSession] = useState<CallSession | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [trtcReady, setTrtcReady] = useState(false)
  const [micOn, setMicOn] = useState(false)
  const [clock, setClock] = useState(() =>
    new Date().toLocaleString('zh-CN', { hour12: false }),
  )

  const remoteRef = useRef<HTMLDivElement>(null)
  const trtcRef = useRef<TrtcClient | null>(null)
  const sessionRef = useRef<CallSession | null>(null)
  sessionRef.current = session

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
    setMicOn(false)
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
    async (call: CallSession, withMic: boolean) => {
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
      if (withMic) {
        try {
          await client.startLocalAudio()
          setMicOn(true)
        } catch (micErr) {
          const msg = micErr instanceof Error ? micErr.message : String(micErr)
          const denied = /NotAllowedError|Permission denied|disabled microphone/i.test(msg)
          setError(
            denied
              ? '浏览器未允许麦克风：可先看画面；要喊话请在地址栏允许麦克风后重新连线'
              : `本地麦克风启动失败：${msg}`,
          )
          setMicOn(false)
        }
      } else {
        setMicOn(false)
      }
      setTrtcReady(true)
    },
    [leaveTrtc],
  )

  const stopCurrentSession = useCallback(async () => {
    const s = sessionRef.current
    if (!s) {
      await leaveTrtc()
      return
    }
    try {
      if (s.kind === 'watch') await endWatch(s.call_id)
      else await endCommandCall(s.call_id)
    } catch {
      /* best-effort */
    }
    await leaveTrtc()
    setSession(null)
  }, [leaveTrtc])

  // 关页 / 刷新：结束监看或连线
  useEffect(() => {
    const onUnload = () => {
      const s = sessionRef.current
      if (!s) return
      const url =
        s.kind === 'watch'
          ? `${(import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8000').replace(/\/$/, '')}/v1/command-call/${encodeURIComponent(s.call_id)}/watch/end`
          : `${(import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8000').replace(/\/$/, '')}/v1/command-call/${encodeURIComponent(s.call_id)}/end`
      try {
        void fetch(url, { method: 'POST', keepalive: true })
      } catch {
        /* ignore */
      }
    }
    window.addEventListener('pagehide', onUnload)
    return () => window.removeEventListener('pagehide', onUnload)
  }, [])

  // 监看心跳
  useEffect(() => {
    if (!isWatchSession(session)) return
    const id = session!.call_id
    const tick = () => {
      void watchHeartbeat(id).catch((e) =>
        setError(e instanceof Error ? e.message : String(e)),
      )
    }
    tick()
    const t = window.setInterval(tick, 10_000)
    return () => window.clearInterval(t)
  }, [session?.call_id, session?.kind, session?.status])

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

  async function openWatch(deviceId: string) {
    if (!deviceId || busy) return
    const device = devices.find((d) => d.id === deviceId)
    if (!device?.inUse || statusClass(device.status) === 'offline') {
      setError('仅已占用且在线的设备可画面监看')
      return
    }
    setBusy(true)
    setError('')
    try {
      if (sessionRef.current) await stopCurrentSession()
      const call = await startWatch(deviceId)
      setSession(call)
      await enterTrtc(call, false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSession(null)
      await leaveTrtc()
    } finally {
      setBusy(false)
    }
  }

  async function onSelectDevice(deviceId: string) {
    if (busy) return
    if (isCallSession(session) && session?.device_id !== deviceId) return
    setSelectedId(deviceId)
    if (session?.device_id === deviceId && (isWatchSession(session) || isCallSession(session))) {
      return
    }
    await openWatch(deviceId)
  }

  async function onStartCall() {
    if (!selectedId || busy) return
    setBusy(true)
    setError('')
    try {
      let call: CallSession
      if (isWatchSession(session) && session?.device_id === selectedId) {
        call = await upgradeWatchToCall(session.call_id)
        setSession(call)
        const client = trtcRef.current
        if (client && trtcReady) {
          try {
            await client.startLocalAudio()
            setMicOn(true)
          } catch (micErr) {
            const msg = micErr instanceof Error ? micErr.message : String(micErr)
            setError(`升级成功但麦克风失败：${msg}`)
          }
        } else {
          await enterTrtc(call, true)
        }
      } else {
        if (sessionRef.current) await stopCurrentSession()
        call = await startCommandCall(selectedId)
        setSession(call)
        await enterTrtc(call, true)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function onStopWatch() {
    if (!session || busy) return
    setBusy(true)
    setError('')
    try {
      await stopCurrentSession()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function onEndCall() {
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

  const watching = isWatchSession(session)
  const inCall = isCallSession(session)

  const selected = useMemo(
    () => devices.find((d) => d.id === selectedId) ?? null,
    [devices, selectedId],
  )

  const callStatusClass =
    session?.status === 'in_call' || session?.status === 'watching'
      ? 'ok'
      : session?.status === 'failed'
        ? 'danger'
        : session?.status === 'connecting'
          ? 'warn'
          : ''

  const stageHint = (() => {
    if (inCall) return '正在建立指挥连线…'
    if (watching) return '正在建立画面监看…'
    if (selected) return '点选设备即开始监看，或直接发起连线'
    return '请先选择左侧设备'
  })()

  return (
    <div className="dashboard">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">赢</span>
          <span className="brand-name">赢筑AI · 指挥连线</span>
        </div>
        <span className="source-tag">执法仪 · TRTC · 画面监看</span>
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
                const selectedCall = (watching || inCall) && session?.device_id === d.id
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
                    onClick={() => void onSelectDevice(d.id)}
                  >
                    <div className="card-preview">
                      {sc === 'recording' ? <span className="rec-tag">● REC</span> : null}
                      {sc === 'offline'
                        ? '⚠ 离线'
                        : selectedCall
                          ? watching
                            ? '👁 监看中'
                            : '📡 连线中'
                          : d.inUse
                            ? '📷 点选监看'
                            : '空闲'}
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
            {trtcReady ? (
              <span className="live-chip">{watching ? '● 监看' : '● LIVE'}</span>
            ) : null}
            <div id="remote-video" className="remote-video" ref={remoteRef} />
            {!trtcReady ? (
              <div className="video-placeholder">
                <div className="icon">{inCall || watching ? '📡' : '📷'}</div>
                <div>{stageHint}</div>
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
                {trtcReady ? (micOn ? '已进房 · 麦开' : '已进房 · 只看') : '未进房'}
              </span>
            </div>
            <div className="param">
              <span className="param-label">会话</span>
              <span className={`param-value ${callStatusClass}`}>
                {session ? `${session.kind}/${session.status}` : '空闲'}
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
                onClick={() => void onStartCall()}
                disabled={!selectedId || inCall || busy}
              >
                {watching ? '升级为连线' : '发起连线'}
              </button>
              <button
                type="button"
                className="ghost-btn"
                onClick={() => void onStopWatch()}
                disabled={!watching || busy}
              >
                停止监看
              </button>
              <button
                type="button"
                className="danger-btn"
                onClick={() => void onEndCall()}
                disabled={!inCall || busy}
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
