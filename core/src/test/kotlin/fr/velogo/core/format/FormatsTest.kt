package fr.velogo.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun `nombres avec virgule decimale`() {
        assertEquals("12,3", Formats.decimal(12.34, 1))
        assertEquals("12,35", Formats.decimal(12.345001, 2))
        assertEquals("0,0", Formats.decimal(0.04, 1))
        assertEquals("−3,5", Formats.decimal(-3.5, 1))
        assertEquals("0,0", Formats.decimal(-0.01, 1))
        assertEquals("100", Formats.decimal(99.6, 0))
        assertEquals("–", Formats.decimal(Double.NaN, 1))
    }

    @Test
    fun `milliers separes par une espace fine au-dela de 9999`() {
        assertEquals("1234", Formats.decimal(1234.0, 0))
        assertEquals("12 345", Formats.decimal(12345.0, 0))
        assertEquals("1 234 567,5", Formats.decimal(1234567.5, 1))
    }

    @Test
    fun `vitesse en km h`() {
        assertEquals("25,0", Formats.speedKmh(25.0 / 3.6))
        assertEquals("0,0", Formats.speedKmh(0.0))
        assertEquals("43,2", Formats.speedKmh(12.0))
        assertEquals("–", Formats.speedKmh(null))
    }

    @Test
    fun `distance en km`() {
        assertEquals("0,00", Formats.distanceKm(0.0))
        assertEquals("12,35", Formats.distanceKm(12_345.0))
        assertEquals("99,99", Formats.distanceKm(99_990.0))
        assertEquals("100,0", Formats.distanceKm(99_996.0))
        assertEquals("123,5", Formats.distanceKm(123_456.0))
        assertEquals("–", Formats.distanceKm(null))
    }

    @Test
    fun `distance lisible`() {
        assertEquals("850 m", Formats.distanceAuto(850.0))
        assertEquals("1,0 km", Formats.distanceAuto(996.0))
        assertEquals("12,3 km", Formats.distanceAuto(12_345.0))
        assertEquals("124 km", Formats.distanceAuto(123_500.0))
    }

    @Test
    fun `pente arrondie au demi-point avec signe`() {
        assertEquals("+4,5", Formats.slope(4.4))
        assertEquals("+4,0", Formats.slope(4.2))
        assertEquals("−7,5", Formats.slope(-7.6))
        assertEquals("0,0", Formats.slope(0.2))
        assertEquals("0,0", Formats.slope(-0.2))
        assertEquals("–", Formats.slope(null))
    }

    @Test
    fun `altitude et denivele en metres entiers`() {
        assertEquals("1234", Formats.meters(1234.4))
        assertEquals("0", Formats.meters(0.3))
        assertEquals("−12", Formats.meters(-12.2))
        assertEquals("–", Formats.meters(null))
    }

    @Test
    fun durees() {
        assertEquals("0:00", Formats.duration(0))
        assertEquals("0:42", Formats.duration(42_000))
        assertEquals("12:05", Formats.duration(725_000))
        assertEquals("1:23:45", Formats.duration(5_025_000))
        assertEquals("10:00:00", Formats.duration(36_000_000))
        assertEquals("0:01", Formats.duration(600))
        assertEquals("0:00", Formats.duration(-5))
    }

    @Test
    fun `durees en toutes lettres`() {
        assertEquals("42 min", Formats.durationWords(42 * 60_000L))
        assertEquals("1 h 05", Formats.durationWords(65 * 60_000L))
        assertEquals("0 min", Formats.durationWords(10_000))
        assertEquals("3 h 00", Formats.durationWords(180 * 60_000L))
    }

    @Test
    fun `tailles de fichiers`() {
        assertEquals("850 ko", Formats.fileSize(850_000))
        assertEquals("47 Mo", Formats.fileSize(47_300_000))
        assertEquals("812 Mo", Formats.fileSize(811_700_000))
        assertEquals("1,0 Go", Formats.fileSize(999_700_000))
        assertEquals("1,3 Go", Formats.fileSize(1_260_000_000))
    }
}
