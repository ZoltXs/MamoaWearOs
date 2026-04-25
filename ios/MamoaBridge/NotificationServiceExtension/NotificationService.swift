import UserNotifications

/**
 Notification Service Extension para procesar notificaciones push.
 
 IMPORTANTE: Esta extension solo puede interceptar notificaciones push
 que lleguen a ESTA app (MamoaBridge). NO puede interceptar notificaciones
 de otras apps como WhatsApp, iMessage, etc.
 
 Para recibir notificaciones de iPhone en Samsung WearOS, se debe usar
 ANCS (Apple Notification Center Service) que ya esta implementado en
 la app WearOS.
 
 Esta extension es util si quieres:
 - Enviar notificaciones push personalizadas desde tu servidor
 - Modificar el contenido de las notificaciones antes de mostrarlas
 - Agregar imagenes o acciones a las notificaciones
 */
class NotificationService: UNNotificationServiceExtension {

    var contentHandler: ((UNNotificationContent) -> Void)?
    var bestAttemptContent: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        bestAttemptContent = (request.content.mutableCopy() as? UNMutableNotificationContent)
        
        guard let bestAttemptContent = bestAttemptContent else {
            contentHandler(request.content)
            return
        }
        
        // Procesar notificacion
        // Aqui puedes:
        // 1. Descargar imagenes para mostrar
        // 2. Modificar el titulo/cuerpo
        // 3. Agregar datos al userInfo
        
        // Ejemplo: agregar badge con datos pendientes de sync
        if let syncPending = bestAttemptContent.userInfo["sync_pending"] as? Int {
            bestAttemptContent.badge = NSNumber(value: syncPending)
        }
        
        // Ejemplo: modificar titulo si viene de WearOS
        if bestAttemptContent.userInfo["source"] as? String == "wearos" {
            bestAttemptContent.title = "Samsung Watch: \(bestAttemptContent.title)"
        }
        
        contentHandler(bestAttemptContent)
    }
    
    override func serviceExtensionTimeWillExpire() {
        // Se llama justo antes de que expire el tiempo limite
        // Entregar lo que tengamos
        if let contentHandler = contentHandler, let bestAttemptContent = bestAttemptContent {
            contentHandler(bestAttemptContent)
        }
    }
}
