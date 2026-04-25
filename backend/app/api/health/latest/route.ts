import { NextRequest, NextResponse } from 'next/server'

// Compartir store con sync route (en produccion usar BD)
const healthDataStore: Map<string, any[]> = new Map()

// Exponer para que sync route pueda escribir (hack temporal)
if (typeof global !== 'undefined') {
  (global as any).healthDataStore = (global as any).healthDataStore || new Map()
}
const getStore = () => (global as any).healthDataStore as Map<string, any[]>

interface LatestData {
  steps?: { value: number; timestamp: number }
  heart_rate?: { value: number; timestamp: number }
  active_calories?: { value: number; timestamp: number }
  distance?: { value: number; timestamp: number }
  sleep?: { value: number; timestamp: number; timestampEnd: number }
  workout?: { value: number; timestamp: number; timestampEnd: number; metadata?: string }
}

/**
 * GET /api/health/latest?deviceId=xxx
 * Obtiene los ultimos valores de cada tipo de dato de salud
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const deviceId = searchParams.get('deviceId')
    
    if (!deviceId) {
      return NextResponse.json(
        { error: 'deviceId parameter required' },
        { status: 400 }
      )
    }
    
    const store = getStore()
    const data = store.get(deviceId) || []
    
    if (data.length === 0) {
      return NextResponse.json({
        success: true,
        deviceId,
        latest: null,
        message: 'No data found for this device'
      })
    }
    
    // Obtener ultimo valor de cada tipo
    const latest: LatestData = {}
    const types = ['steps', 'heart_rate', 'active_calories', 'distance', 'sleep', 'workout']
    
    for (const type of types) {
      const typeData = data.filter((r: any) => r.type === type)
      if (typeData.length > 0) {
        const lastRecord = typeData[typeData.length - 1]
        latest[type as keyof LatestData] = {
          value: lastRecord.value,
          timestamp: lastRecord.timestamp,
          ...(lastRecord.timestampEnd && { timestampEnd: lastRecord.timestampEnd }),
          ...(lastRecord.metadata && { metadata: lastRecord.metadata })
        }
      }
    }
    
    // Calcular totales del dia
    const now = Date.now()
    const startOfDay = new Date().setHours(0, 0, 0, 0)
    
    const todayData = data.filter((r: any) => r.timestamp >= startOfDay)
    
    const todayTotals = {
      steps: todayData.filter((r: any) => r.type === 'steps').reduce((sum: number, r: any) => sum + r.value, 0),
      active_calories: todayData.filter((r: any) => r.type === 'active_calories').reduce((sum: number, r: any) => sum + r.value, 0),
      distance: todayData.filter((r: any) => r.type === 'distance').reduce((sum: number, r: any) => sum + r.value, 0)
    }
    
    return NextResponse.json({
      success: true,
      deviceId,
      latest,
      todayTotals,
      lastSyncAt: data.length > 0 ? data[data.length - 1].receivedAt : null
    })
    
  } catch (error) {
    console.error('[Health Latest] Error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}
