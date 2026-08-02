package com.scorchedphoto.engine.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeaponCatalogTest {

    @Test
    fun `catalog has one weapon per player-selectable type`() {
        // TANK_EXPLOSION is deliberately excluded - it's a tank's own death blast, never
        // fired, selected, or ammo-tracked like the other four.
        val selectableTypes = WeaponType.entries - WeaponType.TANK_EXPLOSION
        assertEquals(selectableTypes.size, WeaponCatalog.all.size)
    }

    @Test
    fun `byType returns the matching weapon`() {
        assertEquals(WeaponCatalog.CLUSTER_MIRV, WeaponCatalog.byType(WeaponType.CLUSTER_MIRV))
    }

    @Test
    fun `baby missile has unlimited ammo`() {
        assertNull(WeaponCatalog.BABY_MISSILE.ammoLimit)
    }

    @Test
    fun `standard shell, big bertha and cluster MIRV have limited starting ammo`() {
        assertEquals(5, WeaponCatalog.STANDARD_SHELL.ammoLimit)
        assertEquals(1, WeaponCatalog.BIG_BERTHA.ammoLimit)
        assertEquals(1, WeaponCatalog.CLUSTER_MIRV.ammoLimit)
    }

    @Test
    fun `cluster MIRV splits radially into multiple children`() {
        assertEquals(4, WeaponCatalog.CLUSTER_MIRV.childCount)
        assertEquals(SplitPattern.RADIAL_FAN, WeaponCatalog.CLUSTER_MIRV.splitPattern)
    }

    @Test
    fun `spread MIRV splits into a horizontal line of 5 children`() {
        assertEquals(5, WeaponCatalog.SPREAD_MIRV.childCount)
        assertEquals(SplitPattern.HORIZONTAL_LINE, WeaponCatalog.SPREAD_MIRV.splitPattern)
    }

    @Test
    fun `nuke has a blast radius 4x big bertha's and inflicts damage-over-time`() {
        assertEquals(WeaponCatalog.BIG_BERTHA.blastRadius * 4f, WeaponCatalog.NUKE.blastRadius, 0.001f)
        assert(WeaponCatalog.NUKE.dotFraction > 0f)
        assert(WeaponCatalog.NUKE.dotRounds > 0)
    }

    @Test
    fun `earthmover fills terrain instead of carving it, and deals no damage`() {
        assertEquals(TerrainEffect.FILL, WeaponCatalog.EARTHMOVER.terrainEffect)
        assertEquals(0, WeaponCatalog.EARTHMOVER.maxDamage)
    }

    @Test
    fun `big bertha has a larger blast radius than standard shell`() {
        assert(WeaponCatalog.BIG_BERTHA.blastRadius > WeaponCatalog.STANDARD_SHELL.blastRadius)
    }
}
