import { useState } from 'react'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { restaurantsApi } from '../../api/restaurants'
import { RestaurantCard } from '../../components/RestaurantCard'
import { PageSpinner } from '../../components/ui/Spinner'

const CITIES = ['Bengaluru', 'Mumbai', 'Delhi', 'Hyderabad', 'Chennai', 'Pune']

export function HomePage() {
  const [city, setCity] = useState('Bengaluru')
  const [page, setPage] = useState(0)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['restaurants', city, page],
    queryFn: () => restaurantsApi.browse({ cityId: city, page, size: 12 }).then((r) => r.data),
    placeholderData: keepPreviousData,
  })

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center gap-3">
        <h1 className="text-xl font-bold text-gray-900 flex-1">Restaurants near you</h1>
        <select
          value={city}
          onChange={(e) => { setCity(e.target.value); setPage(0) }}
          className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:border-orange-500"
        >
          {CITIES.map((c) => <option key={c}>{c}</option>)}
        </select>
      </div>

      {isLoading && <PageSpinner />}

      {isError && (
        <p className="text-center text-red-500 py-10">Failed to load restaurants. Refresh and try again.</p>
      )}

      {data && (
        <>
          {data.content.length === 0 ? (
            <p className="text-center text-gray-400 py-10">No restaurants available in {city}.</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {data.content.map((r) => (
                <RestaurantCard key={r.id} restaurant={r} />
              ))}
            </div>
          )}

          {data.totalPages > 1 && (
            <div className="flex justify-center gap-2 pt-4">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                className="px-4 py-2 text-sm border rounded-lg disabled:opacity-40 hover:bg-gray-100 transition-colors"
              >
                Previous
              </button>
              <span className="px-4 py-2 text-sm text-gray-600">
                {page + 1} / {data.totalPages}
              </span>
              <button
                disabled={data.last}
                onClick={() => setPage((p) => p + 1)}
                className="px-4 py-2 text-sm border rounded-lg disabled:opacity-40 hover:bg-gray-100 transition-colors"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
