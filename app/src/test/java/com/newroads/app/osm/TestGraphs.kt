package com.newroads.app.osm

import kotlin.math.cos

/**
 * Test helpers for building synthetic OSM-like road data. A square grid of streets spaced
 * [spacingMeters] apart, centered on ([centerLat], [centerLon]). Each grid segment is one [RawWay]
 * with two endpoints, mirroring what Overpass "out skel geom" gives us for a straight street.
 */
object TestGraphs {

    fun grid(
        cols: Int,
        rows: Int,
        spacingMeters: Double,
        centerLat: Double,
        centerLon: Double
    ): List<RawWay> {
        val dLat = spacingMeters / 111_000.0
        val dLon = spacingMeters / (111_000.0 * cos(Math.toRadians(centerLat)))

        fun nodeId(r: Int, c: Int): Long = (r.toLong() * cols) + c
        fun lat(r: Int): Double = centerLat + (r - rows / 2) * dLat
        fun lon(c: Int): Double = centerLon + (c - cols / 2) * dLon

        val ways = ArrayList<RawWay>()
        var wayId = 1L
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (c + 1 < cols) {
                    ways.add(
                        RawWay(
                            wayId++,
                            listOf(nodeId(r, c), nodeId(r, c + 1)),
                            listOf(GeoPt(lat(r), lon(c)), GeoPt(lat(r), lon(c + 1)))
                        )
                    )
                }
                if (r + 1 < rows) {
                    ways.add(
                        RawWay(
                            wayId++,
                            listOf(nodeId(r, c), nodeId(r + 1, c)),
                            listOf(GeoPt(lat(r), lon(c)), GeoPt(lat(r + 1), lon(c)))
                        )
                    )
                }
            }
        }
        return ways
    }
}
