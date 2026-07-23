const API_BASE = (import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8000').replace(/\/$/, '')

/** 供关页 keepalive 等与 fetch 共用，避免两处默认地址漂移。 */
export function apiBase(): string {
  return API_BASE
}

export type DeviceRow = {
  id: string
  name: string
  officer: string
  status: string
  inUse: boolean
  lastSeen?: string
  company?: string
}

export type FieldEventTicket = {
  id: string
  company: string
  device_id: string
  employee_id: string
  officer_name: string
  body: string
  status: string
  created_at: string
  updated_at: string
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
  kind: 'watch' | 'call' | string
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

export async function startWatch(deviceId: string, caller = '指挥中心'): Promise<CallSession> {
  const res = await fetch(`${API_BASE}/v1/command-call/watch/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_id: deviceId, caller }),
  })
  return parseJson<CallSession>(res)
}

export async function endWatch(callId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/v1/command-call/${encodeURIComponent(callId)}/watch/end`, {
    method: 'POST',
  })
  await parseJson<{ ok: boolean }>(res)
}

export async function watchHeartbeat(callId: string): Promise<CallSession> {
  const res = await fetch(
    `${API_BASE}/v1/command-call/${encodeURIComponent(callId)}/watch/heartbeat`,
    { method: 'POST' },
  )
  return parseJson<CallSession>(res)
}

export async function upgradeWatchToCall(callId: string): Promise<CallSession> {
  const res = await fetch(`${API_BASE}/v1/command-call/${encodeURIComponent(callId)}/upgrade`, {
    method: 'POST',
  })
  return parseJson<CallSession>(res)
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

export async function fetchFieldEventTickets(company: string): Promise<FieldEventTicket[]> {
  const q = new URLSearchParams({ company })
  const res = await fetch(`${API_BASE}/v1/field-event-tickets?${q}`)
  const data = await parseJson<{ tickets: FieldEventTicket[] }>(res)
  return data.tickets ?? []
}

export async function fetchFieldEventTicket(
  ticketId: string,
  company: string,
): Promise<FieldEventTicket> {
  const q = new URLSearchParams({ company })
  const res = await fetch(
    `${API_BASE}/v1/field-event-tickets/${encodeURIComponent(ticketId)}?${q}`,
  )
  const data = await parseJson<{ ticket: FieldEventTicket }>(res)
  return data.ticket
}

export async function patchFieldEventTicketStatus(
  ticketId: string,
  company: string,
  status: string,
): Promise<FieldEventTicket> {
  const res = await fetch(`${API_BASE}/v1/field-event-tickets/${encodeURIComponent(ticketId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ company, status }),
  })
  const data = await parseJson<{ ticket: FieldEventTicket }>(res)
  return data.ticket
}
