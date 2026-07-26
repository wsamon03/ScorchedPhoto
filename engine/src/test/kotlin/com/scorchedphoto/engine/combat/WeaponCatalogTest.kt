package com.scorchedphoto.engine.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
        assertEquals(WeaponCatalog.MIRV, WeaponCatalog.byType(WeaponType.MIRV))
    }

    @Test
    fun `standard shell and baby missile have unlimited ammo`() {
        assertNull(WeaponCatalog.STANDARD_SHELL.ammoLimit)
        assertNull(WeaponCatalog.BABY_MISSILE.ammoLimit)
    }

    @Test
    fun `big bertha and MIRV have limited ammo`() {
        assertNotNull(WeaponCatalog.BIG_BERTHA.ammoLimit)
        assertNotNull(WeaponCatalog.MIRV.ammoLimit)
    }

    @Test
    fun `MIRV splits into multiple children`() {
        assertEquals(4, WeaponCatalog.MIRV.childCount)
    }

    @Test
    fun `big bertha has a larger blast radius than standard shell`() {
        assert(WeaponCatalog.BIG_BERTHA.blastRadius > WeaponCatalog.STANDARD_SHELL.blastRadius)
    }
}
