package app.lockmein.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A city to work out fajr from, when the phone's location is not given. */
data class Place(val country: String, val city: String, val latitude: Double, val longitude: Double) {
    val name get() = "$city, $country"
}

/**
 * Cities to pick from, by country, and the sun angle each country's mosques use for fajr.
 * Coordinates are the city centre (to 4 decimals, about 10 m; a degree of longitude moves fajr by 4 minutes).
 */
object Places {
    /** The fajr sun angle used in [country]; 18 (Muslim World League) where no local rule is known. */
    fun angleFor(country: String): Double = ANGLES[country] ?: 18.0

    fun search(query: String): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return ALL
        return ALL.filter { it.city.contains(q, ignoreCase = true) || it.country.contains(q, ignoreCase = true) }
    }

    /** The listed city nearest to a point, with its distance in km. */
    fun nearest(latitude: Double, longitude: Double): Pair<Place, Double> =
        ALL.map { it to km(latitude, longitude, it.latitude, it.longitude) }.minBy { it.second }

    /** What to call a point: the city when it is within [withinKm], else null. */
    fun nameOf(latitude: Double, longitude: Double, withinKm: Double = 30.0): String? =
        nearest(latitude, longitude).takeIf { it.second <= withinKm }?.first?.name

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }

    private val ANGLES = mapOf(
        "Morocco" to 19.0,
        "Egypt" to 19.5,
        "Saudi Arabia" to 18.5,
        "United Arab Emirates" to 18.2,
        "United States" to 15.0,
        "Canada" to 15.0,
        "Indonesia" to 20.0,
    )

    private fun c(country: String, vararg cities: Triple<String, Double, Double>) = cities.map { Place(country, it.first, it.second, it.third) }
    private fun t(city: String, lat: Double, lon: Double) = Triple(city, lat, lon)

    val ALL: List<Place> = (
        c("Morocco",
            t("Agadir", 30.4278, -9.5981), t("Inezgane", 30.3553, -9.5361), t("Taroudant", 30.4703, -8.8770), t("Tiznit", 29.6974, -9.7316),
            t("Guelmim", 28.9870, -10.0574), t("Laayoune", 27.1253, -13.1625), t("Dakhla", 23.6848, -15.9580), t("Essaouira", 31.5085, -9.7595),
            t("Marrakech", 31.6295, -7.9811), t("Ouarzazate", 30.9189, -6.8934), t("Errachidia", 31.9314, -4.4243), t("Safi", 32.2994, -9.2372),
            t("El Jadida", 33.2316, -8.5007), t("Casablanca", 33.5731, -7.5898), t("Mohammedia", 33.6866, -7.3830), t("Berrechid", 33.2655, -7.5875),
            t("Settat", 33.0010, -7.6166), t("Khouribga", 32.8811, -6.9063), t("Beni Mellal", 32.3373, -6.3498), t("Rabat", 34.0209, -6.8416),
            t("Sale", 34.0531, -6.7985), t("Temara", 33.9287, -6.9063), t("Kenitra", 34.2610, -6.5802), t("Khemisset", 33.8241, -6.0663),
            t("Sidi Kacem", 34.2260, -5.7079), t("Meknes", 33.8935, -5.5473), t("Ifrane", 33.5228, -5.1106), t("Fes", 34.0181, -5.0078),
            t("Taza", 34.2100, -4.0100), t("Oujda", 34.6814, -1.9086), t("Taourirt", 34.4073, -2.8973), t("Nador", 35.1681, -2.9335),
            t("Al Hoceima", 35.2517, -3.9372), t("Chefchaouen", 35.1688, -5.2636), t("Tetouan", 35.5785, -5.3684), t("Tangier", 35.7595, -5.8340),
            t("Larache", 35.1932, -6.1557), t("Ksar El Kebir", 35.0017, -5.9093)) +
        c("Algeria", t("Algiers", 36.7538, 3.0588), t("Oran", 35.6971, -0.6308), t("Constantine", 36.3650, 6.6147)) +
        c("Tunisia", t("Tunis", 36.8065, 10.1815), t("Sfax", 34.7406, 10.7603)) +
        c("Mauritania", t("Nouakchott", 18.0735, -15.9582)) +
        c("Senegal", t("Dakar", 14.7167, -17.4677)) +
        c("Egypt", t("Cairo", 30.0444, 31.2357), t("Alexandria", 31.2001, 29.9187)) +
        c("Saudi Arabia", t("Mecca", 21.3891, 39.8579), t("Medina", 24.5247, 39.5692), t("Riyadh", 24.7136, 46.6753), t("Jeddah", 21.4858, 39.1925)) +
        c("United Arab Emirates", t("Dubai", 25.2048, 55.2708), t("Abu Dhabi", 24.4539, 54.3773)) +
        c("Qatar", t("Doha", 25.2854, 51.5310)) +
        c("Kuwait", t("Kuwait City", 29.3759, 47.9774)) +
        c("Jordan", t("Amman", 31.9454, 35.9284)) +
        c("Turkey", t("Istanbul", 41.0082, 28.9784), t("Ankara", 39.9334, 32.8597)) +
        c("Pakistan", t("Karachi", 24.8607, 67.0011)) +
        c("Malaysia", t("Kuala Lumpur", 3.1390, 101.6869)) +
        c("Indonesia", t("Jakarta", -6.2088, 106.8456)) +
        c("France",
            t("Paris", 48.8566, 2.3522), t("Lyon", 45.7640, 4.8357), t("Marseille", 43.2965, 5.3698), t("Toulouse", 43.6047, 1.4442),
            t("Lille", 50.6292, 3.0573), t("Bordeaux", 44.8378, -0.5792), t("Nice", 43.7102, 7.2620), t("Strasbourg", 48.5734, 7.7521),
            t("Montpellier", 43.6108, 3.8767), t("Nantes", 47.2184, -1.5536)) +
        c("Belgium", t("Brussels", 50.8503, 4.3517), t("Antwerp", 51.2194, 4.4025), t("Liege", 50.6326, 5.5797)) +
        c("Netherlands", t("Amsterdam", 52.3676, 4.9041), t("Rotterdam", 51.9244, 4.4777), t("The Hague", 52.0705, 4.3007), t("Utrecht", 52.0907, 5.1214)) +
        c("Spain",
            t("Madrid", 40.4168, -3.7038), t("Barcelona", 41.3874, 2.1686), t("Valencia", 39.4699, -0.3763), t("Seville", 37.3891, -5.9845),
            t("Malaga", 36.7213, -4.4214), t("Murcia", 37.9922, -1.1307), t("Almeria", 36.8381, -2.4597)) +
        c("Italy", t("Rome", 41.9028, 12.4964), t("Milan", 45.4642, 9.1900), t("Turin", 45.0703, 7.6869), t("Bologna", 44.4949, 11.3426)) +
        c("Germany",
            t("Berlin", 52.5200, 13.4050), t("Hamburg", 53.5511, 9.9937), t("Munich", 48.1351, 11.5820), t("Frankfurt", 50.1109, 8.6821),
            t("Cologne", 50.9375, 6.9603), t("Dusseldorf", 51.2277, 6.7735)) +
        c("Switzerland", t("Geneva", 46.2044, 6.1432), t("Zurich", 47.3769, 8.5417)) +
        c("United Kingdom", t("London", 51.5074, -0.1278), t("Birmingham", 52.4862, -1.8904), t("Manchester", 53.4808, -2.2426)) +
        c("United States", t("New York", 40.7128, -74.0060), t("Chicago", 41.8781, -87.6298), t("Houston", 29.7604, -95.3698), t("Los Angeles", 34.0522, -118.2437)) +
        c("Canada", t("Montreal", 45.5019, -73.5674), t("Toronto", 43.6532, -79.3832))
    ).sortedWith(compareBy({ it.country }, { it.city }))
}
