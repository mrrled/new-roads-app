package com.newroads.app.osm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for large-area routes silently failing: a valid-but-empty response (e.g. from a
 * regional Overpass mirror that has no data for the queried area) must be recognisable as having no
 * roads, so it never beats a real response in the parallel race.
 */
class OverpassResponseTest {

    private val client = OverpassClient()

    @Test
    fun hasRoads_trueWhenWaysPresent() {
        val json = """{"elements":[{"type":"way","id":1,"nodes":[1,2],"geometry":[]}]}"""
        assertTrue(client.hasRoads(json))
    }

    @Test
    fun hasRoads_falseForEmptyElements() {
        val json = """{"version":0.6,"generator":"Overpass","elements":[]}"""
        assertFalse("an empty regional-mirror response must not count as having roads", client.hasRoads(json))
    }
}
