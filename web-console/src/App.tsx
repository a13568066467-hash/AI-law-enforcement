import { useCallback, useEffect, useRef, useState } from 'react'
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

export default function App() {
  const [devices, setDevices] = useState<DeviceRow[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [session, setSession] = useState<CallSession | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [trtcReady, setTrtcReady] = useState(false)

  const remoteRef = useRef<HTMLDivElement>(null)
  const trtcRef = useRef<TrtcClient | null>(null)

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

  return (
    <div className="page">
      <header className="header">
        <h1>指挥连线台</h1>
        <p className="sub">腾讯云 TRTC · 平台呼叫执法仪</p>
      </header>

      <section className="panel">
        <div className="row">
          <label htmlFor="device">设备</label>
          <select
            id="device"
            value={selectedId}
            disabled={inCall || busy}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            {devices.length === 0 && <option value="">（无设备）</option>}
            {devices.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name || d.id} · {d.officer} · {d.inUse ? '已占用' : '空闲'} · {d.status}
              </option>
            ))}
          </select>
          <button type="button" onClick={() => void refreshDevices()} disabled={busy}>
            刷新
          </button>
        </div>

        <div className="actions">
          <button type="button" className="primary" onClick={() => void onStart()} disabled={!selectedId || inCall || busy}>
            发起连线
          </button>
          <button type="button" className="danger" onClick={() => void onEnd()} disabled={!session || busy}>
            结束连线
          </button>
        </div>

        <div className="status">
          <div>
            会话状态：<strong>{session?.status ?? '空闲'}</strong>
            {session?.call_id ? ` · ${session.call_id}` : ''}
          </div>
          <div>TRTC：{trtcReady ? '已进房（麦已开）' : '未进房'}</div>
          {session?.failure_reason ? (
            <div className="fail">失败原因：{session.failure_reason}</div>
          ) : null}
          {error ? <div className="fail">{error}</div> : null}
        </div>
      </section>

      <section className="video-panel">
        <h2>远端画面（设备）</h2>
        <div id="remote-video" className="remote" ref={remoteRef} />
      </section>
    </div>
  )
}
