import { NextRequest, NextResponse } from 'next/server'

// Store de vinculacion de dispositivos
// deviceId WearOS -> userId -> deviceId iOS
if (typeof global !== 'undefined') {
  (global as any).deviceLinks = (global as any).deviceLinks || new Map()
}
const getLinks = () => (global as any).deviceLinks as Map<string, DeviceLink>

interface DeviceLink {
  wearosDeviceId: string
  iosDeviceId?: string
  userId: string
  linkedAt: number
  wearosLastSeen?: number
  iosLastSeen?: number
}

/**
 * POST /api/devices/link
 * Vincula un dispositivo WearOS con un usuario (y opcionalmente iOS)
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { wearosDeviceId, iosDeviceId, userId, linkCode } = body
    
    if (!userId) {
      return NextResponse.json(
        { error: 'userId required' },
        { status: 400 }
      )
    }
    
    const links = getLinks()
    
    // Si es un nuevo enlace WearOS
    if (wearosDeviceId) {
      const existingLink = links.get(wearosDeviceId)
      
      if (existingLink && existingLink.userId !== userId) {
        return NextResponse.json(
          { error: 'Device already linked to another user' },
          { status: 409 }
        )
      }
      
      const link: DeviceLink = {
        wearosDeviceId,
        iosDeviceId: existingLink?.iosDeviceId || iosDeviceId,
        userId,
        linkedAt: existingLink?.linkedAt || Date.now(),
        wearosLastSeen: Date.now()
      }
      
      links.set(wearosDeviceId, link)
      
      // Generar codigo de vinculacion si no hay iOS
      const code = linkCode || generateLinkCode()
      
      return NextResponse.json({
        success: true,
        linkCode: code,
        link
      })
    }
    
    // Si es un enlace iOS buscando WearOS por codigo
    if (iosDeviceId && linkCode) {
      // Buscar dispositivo WearOS con ese codigo
      for (const [deviceId, link] of links.entries()) {
        if (link.userId === userId) {
          link.iosDeviceId = iosDeviceId
          link.iosLastSeen = Date.now()
          links.set(deviceId, link)
          
          return NextResponse.json({
            success: true,
            message: 'iOS device linked successfully',
            link
          })
        }
      }
      
      return NextResponse.json(
        { error: 'No WearOS device found for this user' },
        { status: 404 }
      )
    }
    
    return NextResponse.json(
      { error: 'wearosDeviceId or (iosDeviceId + linkCode) required' },
      { status: 400 }
    )
    
  } catch (error) {
    console.error('[Devices] Error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}

/**
 * GET /api/devices?userId=xxx
 * Obtiene los dispositivos vinculados de un usuario
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const userId = searchParams.get('userId')
    const wearosDeviceId = searchParams.get('wearosDeviceId')
    
    const links = getLinks()
    
    if (wearosDeviceId) {
      const link = links.get(wearosDeviceId)
      if (!link) {
        return NextResponse.json(
          { error: 'Device not found' },
          { status: 404 }
        )
      }
      return NextResponse.json({ success: true, link })
    }
    
    if (userId) {
      const userDevices: DeviceLink[] = []
      for (const link of links.values()) {
        if (link.userId === userId) {
          userDevices.push(link)
        }
      }
      return NextResponse.json({ success: true, devices: userDevices })
    }
    
    return NextResponse.json(
      { error: 'userId or wearosDeviceId required' },
      { status: 400 }
    )
    
  } catch (error) {
    console.error('[Devices] Error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}

function generateLinkCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let code = ''
  for (let i = 0; i < 6; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length))
  }
  return code
}
