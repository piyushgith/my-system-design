import { MapContainer, TileLayer, Marker, Polyline } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const markerIcon = L.divIcon({
  className: 'custom-marker',
  html: '<div class="marker-dot"></div>',
  iconSize: [16, 16],
  iconAnchor: [8, 8],
})

const driverIcon = L.divIcon({
  className: 'custom-marker driver',
  html: '<div class="marker-dot driver"></div>',
  iconSize: [20, 20],
  iconAnchor: [10, 10],
})

type TripMapProps = {
  pickup?: { lat: number; lng: number }
  destination?: { lat: number; lng: number }
  driver?: { lat: number; lng: number } | null
  mapKey?: string
  className?: string
}

export const TripMap = ({ pickup, destination, driver, mapKey = 'default', className = '' }: TripMapProps) => {
  const center = pickup ?? destination ?? { lat: 12.9716, lng: 77.5946 }
  const points: [number, number][] = []
  if (pickup) points.push([pickup.lat, pickup.lng])
  if (destination) points.push([destination.lat, destination.lng])

  return (
    <div className={`overflow-hidden rounded-2xl border border-border ${className}`}>
      <MapContainer
        key={mapKey}
        center={[center.lat, center.lng]}
        zoom={13}
        scrollWheelZoom={false}
        className="h-full min-h-[240px] w-full"
        style={{ background: '#0F1117' }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>'
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        />
        {pickup && <Marker position={[pickup.lat, pickup.lng]} icon={markerIcon} />}
        {destination && <Marker position={[destination.lat, destination.lng]} icon={markerIcon} />}
        {driver && <Marker position={[driver.lat, driver.lng]} icon={driverIcon} />}
        {points.length === 2 && (
          <Polyline positions={points} pathOptions={{ color: '#F5A623', weight: 3, opacity: 0.7, dashArray: '8 8' }} />
        )}
      </MapContainer>
    </div>
  )
}
