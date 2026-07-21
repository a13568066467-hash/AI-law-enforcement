const API_BASE = (import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8000').replace(/\/$/, '')

export type DeviceRow = {
  id: string
  name: string
  officer: string
  status: string
  inUse: boolean
  lastSeen?: string
}

export type PlatformCreds = {
  sdk_app_id: number
  room_id: string
  user_id: string
  user_sig: string
}

export type CallSession = {
  call_id: string
  device_id: string
  room_id: string
  caller: string
  status: string
  failure_reason?: string
  platform: PlatformCreds
}

async function parseJson<T>(res: Response): Promise<T> {
  const text = await res.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!res.ok) {
    const detail =
      typeof body === 'object' && body && 'detail' in body
        ? String((body as { detail: unknown }).detail)
        : text || res.statusText
    throw new Error(detail || `HTTP ${res.status}`)
  }
  return body as T
}

export async function fetchDevices(): Promise<DeviceRow[]> {
  const res = await fetch(`${API_BASE}/v1/dashboard/devices`)
  const data = await parseJson<{ devices: DeviceRow[] }>(res)
  return data.devices ?? []
}

export async function startCommandCall(deviceId: string, caller = '指挥中心'): Promise<CallSession> {
  const res = await fetch(`${API_BASE}/v1/command-call/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_id: deviceId, caller }),
  })
  return parseJson<CallSession>(res)
}

export async function getCommandCall(callId: string): Promise<CallSession> {
  const res = await fetch(`${API_BASE}/v1/command-call/${encodeURIComponent(callId)}`)
  return parseJson<CallSession>(res)
}

export async function endCommandCall(callId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/v1/command-call/${encodeURIComponent(callId)}/end`, {
    method: 'POST',
  })
  await parseJson<{ ok: boolean }>(res)
}
