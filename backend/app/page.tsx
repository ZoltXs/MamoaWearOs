export default function Home() {
  return (
    <main style={{ fontFamily: 'system-ui', padding: '2rem', maxWidth: '800px', margin: '0 auto' }}>
      <h1>Mamoa Health Sync API</h1>
      <p>Backend para sincronizar datos de salud entre Samsung WearOS y Apple HealthKit.</p>
      
      <h2>Endpoints</h2>
      
      <section style={{ marginBottom: '2rem' }}>
        <h3>POST /api/health/sync</h3>
        <p>Recibe datos de salud del WearOS.</p>
        <pre style={{ background: '#f5f5f5', padding: '1rem', borderRadius: '8px', overflow: 'auto' }}>
{`{
  "deviceId": "wearos_xxx",
  "platform": "wearos",
  "appVersion": "3.0",
  "data": [
    {
      "type": "steps",
      "value": 100,
      "unit": "count",
      "timestamp": 1700000000000
    },
    {
      "type": "heart_rate",
      "value": 72,
      "unit": "bpm",
      "timestamp": 1700000000000
    }
  ]
}`}
        </pre>
      </section>
      
      <section style={{ marginBottom: '2rem' }}>
        <h3>GET /api/health/sync?deviceId=xxx</h3>
        <p>Obtiene datos de salud para sincronizar con iOS.</p>
        <p>Parametros opcionales: since (timestamp), type, limit</p>
      </section>
      
      <section style={{ marginBottom: '2rem' }}>
        <h3>GET /api/health/latest?deviceId=xxx</h3>
        <p>Obtiene los ultimos valores de cada tipo de dato.</p>
      </section>
      
      <section style={{ marginBottom: '2rem' }}>
        <h3>POST /api/devices/link</h3>
        <p>Vincula dispositivos WearOS e iOS.</p>
      </section>
      
      <section style={{ marginBottom: '2rem' }}>
        <h3>GET /api/devices?userId=xxx</h3>
        <p>Obtiene dispositivos vinculados de un usuario.</p>
      </section>
      
      <footer style={{ marginTop: '3rem', color: '#666', fontSize: '0.9rem' }}>
        <p>Mamoa Health Sync - Backend v1.0</p>
      </footer>
    </main>
  )
}
