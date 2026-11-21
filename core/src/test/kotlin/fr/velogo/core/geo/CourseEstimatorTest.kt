package fr.velogo.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CourseEstimatorTest {
    private val start = LatLon(42.3, 9.15)

    /** Écart entre deux caps, en degrés, par le plus court chemin. */
    private fun turn(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    /** Roule en ligne droite : une position par seconde, sans cap ni vitesse donnés par le GPS. */
    private fun ride(estimator: CourseEstimator, from: LatLon, bearing: Double, speedMps: Double, seconds: Int, startMs: Long): Pair<LatLon, Course> {
        var p = from
        var course = Course(null, 0.0)
        for (s in 1..seconds) {
            p = Geo.offset(p.lat, p.lon, bearing, speedMps)
            course = estimator.update(p.lat, p.lon, startMs + s * 1000L)
        }
        return p to course
    }

    @Test
    fun `cap et vitesse deduits des positions`() {
        val estimator = CourseEstimator()
        assertNull(estimator.update(start.lat, start.lon, 0).bearingDeg)

        val (_, course) = ride(estimator, start, bearing = 40.0, speedMps = 6.9, seconds = 6, startMs = 0)

        assertEquals(40.0, course.bearingDeg!!, 0.5)
        assertEquals(6.9, course.speedMps, 0.1)
    }

    @Test
    fun `le cap arrive des qu'on a assez avance`() {
        val estimator = CourseEstimator(baselineM = 8.0)
        estimator.update(start.lat, start.lon, 0)
        // À 5 m/s : pas encore de cap après une seconde (5 m), un cap après deux (10 m).
        val one = Geo.offset(start.lat, start.lon, 270.0, 5.0)
        assertNull(estimator.update(one.lat, one.lon, 1_000).bearingDeg)
        val two = Geo.offset(start.lat, start.lon, 270.0, 10.0)
        assertEquals(270.0, estimator.update(two.lat, two.lon, 2_000).bearingDeg!!, 0.5)
    }

    @Test
    fun `le cap suit un virage`() {
        val estimator = CourseEstimator()
        estimator.update(start.lat, start.lon, 0)
        val (corner, east) = ride(estimator, start, bearing = 90.0, speedMps = 6.0, seconds = 10, startMs = 0)
        assertEquals(90.0, east.bearingDeg!!, 0.5)

        // Virage à gauche, plein nord : quelques secondes plus tard la flèche a tourné.
        val (_, north) = ride(estimator, corner, bearing = 0.0, speedMps = 6.0, seconds = 4, startMs = 10_000)
        assertEquals(0.0, turn(north.bearingDeg!!, 0.0), 12.0)
    }

    @Test
    fun `a l'arret le dernier cap est garde malgre le bruit du GPS`() {
        val estimator = CourseEstimator()
        estimator.update(start.lat, start.lon, 0)
        val (stop, moving) = ride(estimator, start, bearing = 130.0, speedMps = 7.0, seconds = 8, startMs = 0)
        assertEquals(130.0, moving.bearingDeg!!, 0.5)

        // Deux minutes à l'arrêt : la position annoncée tremble de quelques mètres autour du même point.
        val random = Random(7)
        val bearings = ArrayList<Double>()
        var course = moving
        for (s in 1..120) {
            val noisy = Geo.offset(stop.lat, stop.lon, random.nextDouble(360.0), random.nextDouble(3.0))
            course = estimator.update(noisy.lat, noisy.lon, 8_000 + s * 1000L)
            bearings.add(course.bearingDeg!!)
        }

        // La flèche montre toujours, à peu de chose près, la direction dans laquelle on roulait…
        assertEquals(0.0, turn(course.bearingDeg!!, 130.0), 15.0)
        // … et une fois l'arrêt constaté (quelques secondes), elle ne bouge plus du tout.
        assertEquals(1, bearings.drop(6).toSet().size)
        assertEquals(0.0, course.speedMps, 2.5)
    }

    @Test
    fun `une lente derive a l'arret ne donne pas de cap`() {
        val estimator = CourseEstimator()
        // 0,5 m par seconde : on atteint bien 8 m de recul, mais en seize secondes. Ce n'est pas rouler.
        var p = start
        var course = estimator.update(p.lat, p.lon, 0)
        for (s in 1..40) {
            p = Geo.offset(p.lat, p.lon, 200.0, 0.5)
            course = estimator.update(p.lat, p.lon, s * 1000L)
        }
        assertNull(course.bearingDeg)
    }

    @Test
    fun `le cap et la vitesse du GPS sont pris quand il les donne`() {
        val estimator = CourseEstimator()
        // Les positions partent vers l'est, mais le GPS annonce 75° à 6 m/s : c'est lui qu'on croit.
        var p = start
        var course = Course(null, 0.0)
        for (s in 0..5) {
            course = estimator.update(p.lat, p.lon, s * 1000L, gpsBearingDeg = 75.0, gpsSpeedMps = 6.0)
            p = Geo.offset(p.lat, p.lon, 90.0, 6.0)
        }
        assertEquals(75.0, course.bearingDeg!!, 1e-9)
        assertEquals(6.0, course.speedMps, 1e-9)

        // Presque à l'arrêt, le GPS annonce un cap fantaisiste : on garde le précédent.
        course = estimator.update(p.lat, p.lon, 6_000, gpsBearingDeg = 301.0, gpsSpeedMps = 0.3)
        assertEquals(75.0, course.bearingDeg!!, 1e-9)
        assertEquals(0.3, course.speedMps, 1e-9)

        // Un cap négatif ou au-delà de 360° est ramené dans le tour.
        course = estimator.update(p.lat, p.lon, 7_000, gpsBearingDeg = -90.0, gpsSpeedMps = 4.0)
        assertEquals(270.0, course.bearingDeg!!, 1e-9)
    }

    @Test
    fun `un saut de position fait repartir de zero`() {
        val estimator = CourseEstimator()
        estimator.update(start.lat, start.lon, 0)
        val (_, moving) = ride(estimator, start, bearing = 10.0, speedMps = 6.0, seconds = 5, startMs = 0)
        assertNotNull(moving.bearingDeg)

        // La position réapparaît à trois kilomètres (GPS qui retrouve ses satellites, position simulée déplacée).
        val far = Geo.offset(start.lat, start.lon, 180.0, 3_000.0)
        val course = estimator.update(far.lat, far.lon, 6_000)
        assertNull(course.bearingDeg)
        assertEquals(0.0, course.speedMps, 1e-9)
    }

    @Test
    fun `une horloge qui recule fait repartir de zero`() {
        val estimator = CourseEstimator()
        estimator.update(start.lat, start.lon, 50_000)
        ride(estimator, start, bearing = 10.0, speedMps = 6.0, seconds = 5, startMs = 50_000)
        val p = Geo.offset(start.lat, start.lon, 10.0, 40.0)
        assertNull(estimator.update(p.lat, p.lon, 1_000).bearingDeg)
    }
}
