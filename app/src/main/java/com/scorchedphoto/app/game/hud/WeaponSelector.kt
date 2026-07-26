package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.WeaponHudInfo
import com.scorchedphoto.engine.combat.WeaponType

private fun WeaponType.displayName(): String = when (this) {
    WeaponType.STANDARD_SHELL -> "Shell"
    WeaponType.BIG_BERTHA -> "Bertha"
    WeaponType.MIRV -> "MIRV"
    WeaponType.BABY_MISSILE -> "Baby"
    // Never actually reaches this selector - WeaponCatalog.all excludes it (a tank's own
    // death blast, not a player-selectable weapon) - see WeaponCatalog.TANK_DEATH_EXPLOSION.
    WeaponType.TANK_EXPLOSION -> "Explosion"
}

@Composable
fun WeaponSelector(
    weapons: List<WeaponHudInfo>,
    enabled: Boolean,
    onCommand: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        weapons.forEach { weapon ->
            val outOfAmmo = weapon.ammoRemaining == 0
            FilterChip(
                selected = weapon.selected,
                enabled = enabled && !outOfAmmo,
                onClick = { onCommand(GameCommand.SetWeapon(weapon.weaponType)) },
                label = {
                    val ammoLabel = weapon.ammoRemaining?.toString() ?: "∞"
                    Text("${weapon.weaponType.displayName()} ($ammoLabel)")
                },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}
