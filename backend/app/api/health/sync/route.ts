import { NextRequest, NextResponse } from 'next/server'

// In-memory storage (en produccion usar Supabase/Postgres)
// Este es un MVP simple - para produccion se recomienda conectar a una BD
const healthDataStore: Map<string, HealthRecord[]> = new Map()

interface HealthDataPayload {
  type: string
  value: number
  valueSecondary?: number
  unit: string
  timestamp: number
  timestampEnd?: number
  metadata?: string
  source?: string
}

interface SyncPayload {
  deviceId: string
  platform: string
  appVersion: string
  data: HealthDataPayload[]
}

interface HealthRecord extends HealthDataPayload {
  id: string
  deviceId: string
  receivedAt: number
}

/**
 * POST /api/health/sync
 * Recibe datos de salud del WearOS y los almacena
 */
export async function POST(request: NextRequest) {
  try {
    const body: SyncPayload = await request.json()
    
    if (!body.deviceId || !body.data || !Array.isArray(body.data)) {
      return NextResponse.json(
        { error: 'Invalid payload: deviceId and data array required' },
        { status: 400 }
      )
    }
    
    const records: HealthRecord[] = body.data.map((item, index) => ({
      ...item,
      id: `${body.deviceId}_${item.timestamp}_${index}`,
      deviceId: body.deviceId,
      receivedAt: Date.now()
    }))
    
    // Almacenar datos
    const existingData = healthDataStore.get(body.deviceId) || []
    healthDataStore.set(body.deviceId, [...existingData, ...records])
    
    // Limitar a ultimos 10000 registros por dispositivo
    const deviceData = healthDataStore.get(body.deviceId)!
    if (deviceData.length > 10000) {
      healthDataStore.set(body.deviceId, deviceData.slice(-10000))
    }
    
    console.log(`[Health Sync] Received ${records.length} records from ${body.deviceId} (${body.platform})`)
    
    return NextResponse.json({
      success: true,
      received: records.length,
      deviceId: body.deviceId
    })
    
  } catch (error) {
    console.error('[Health Sync] Error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}

/**
 * GET /api/health/sync?deviceId=xxx&since=timestamp
 * Obtiene datos de salud para sincronizar con iOS
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const deviceId = searchParams.get('deviceId')
    const since = searchParams.get('since')
    const type = searchParams.get('type')
    const limit = parseInt(searchParams.get('limit') || '100')
    
    if (!deviceId) {
      return NextResponse.json(
        { error: 'deviceId parameter required' },
        { status: 400 }
      )
    }
    
    let data = healthDataStore.get(deviceId) || []
    
    // Filtrar por timestamp
    if (since) {
      const sinceTs = parseInt(since)
      data = data.filter(r => r.timestamp > sinceTs)
    }
    
    // Filtrar por tipo
    if (type) {
      data = data.filter(r => r.type === type)
    }
    
    // Limitar resultados
    data = data.slice(-limit)
    
    return NextResponse.json({
      success: true,
      deviceId,
      count: data.length,
      data
    })
    
  } catch (error) {
    console.error('[Health Sync] Error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}
