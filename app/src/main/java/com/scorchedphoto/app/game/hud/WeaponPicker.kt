package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scorchedphoto.app.game.GameCommand
import com.scorchedphoto.app.game.WeaponHudInfo
import com.scorchedphoto.engine.combat.WeaponType

private fun WeaponType.displayName(): String = when (this) {
    WeaponType.STANDARD_SHELL -> "Shell"
    WeaponType.BIG_BERTHA -> "Bertha"
    WeaponType.MIRV -> "MIRV"
    WeaponType.BABY_MISSILE -> "Baby"
    // Never actually reaches this picker - WeaponCatalog.all excludes it (a tank's own
    // death blast, not a player-selectable weapon) - see WeaponCatalog.TANK_DEATH_EXPLOSION.
    WeaponType.TANK_EXPLOSION -> "Explosion"
}

/** Compact "current weapon + quantity" control, docked next to the fire button (see
 * [HudOverlay]) - a single pill showing the selected weapon and a dropdown-chevron
 * affordance; tapping it opens a [DropdownMenu] listing every weapon and its remaining
 * quantity, mirroring the "tap a compact trigger, get a DropdownMenu" idiom
 * [com.scorchedphoto.app.setup.GameSettingsScreen]'s `TypeDropdown` already establishes
 * elsewhere in this app. */
@Composable
fun WeaponPicker(
    weapons: List<WeaponHudInfo>,
    enabled: Boolean,
    onCommand: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = weapons.firstOrNull { it.selected }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val ammoLabel = selected?.ammoRemaining?.toString() ?: "∞"
            Text(
                text = "${selected?.weaponType?.displayName() ?: "-"} ($ammoLabel)",
                color = Color.White,
                fontSize = 13.sp,
            )
            Spacer(Modifier.width(4.dp))
            // Small dropdown-chevron affordance signaling this is a picker, not just a label.
            Text(text = "▾", color = Color.White, fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            weapons.forEach { weapon ->
                val outOfAmmo = weapon.ammoRemaining == 0
                DropdownMenuItem(
                    text = {
                        val ammoLabel = weapon.ammoRemaining?.toString() ?: "∞"
                        Text("${weapon.weaponType.displayName()} ($ammoLabel)")
                    },
                    enabled = !outOfAmmo,
                    onClick = {
                        onCommand(GameCommand.SetWeapon(weapon.weaponType))
                        expanded = false
                    },
                )
            }
        }
    }
}
