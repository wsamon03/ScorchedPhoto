package com.scorchedphoto.app.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorchedphoto.app.game.turntransition.PassDeviceScreen
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType

/**
 * The weapon shop - reached before every game's first match, and (see [com.scorchedphoto.app.navigation.ScorchedNavGraph])
 * between rounds of an ongoing tournament, never on an ordinary single-game Rematch. Cycles
 * through [ShopViewModel.humanShoppers] one at a time via [PassDeviceScreen] the same way
 * [com.scorchedphoto.app.game.GameScreen] hands the device off between human turns - CPU tanks
 * never appear here, since [ShopViewModel] already auto-purchased for them on construction.
 * Calls [onContinue] once every human shopper has confirmed (a "Continue" tap with nothing
 * bought is how a player skips spending entirely - no separate skip affordance needed).
 */
@Composable
fun ShopScreen(onContinue: () -> Unit, viewModel: ShopViewModel = hiltViewModel()) {
    // Read purely to make this composable recompose after a buy/sell mutates ShopViewModel's
    // otherwise-plain (non-Compose-observable) repository maps - see ShopViewModel's own doc.
    viewModel.version.collectAsStateWithLifecycle().value

    val shoppers = viewModel.humanShoppers
    var shopperIndex by remember { mutableStateOf(0) }
    var acknowledgedIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(shopperIndex) {
        if (shopperIndex >= shoppers.size) onContinue()
    }

    if (shopperIndex >= shoppers.size) return
    val shopper = shoppers[shopperIndex]

    // Shown once per new human shopper's turn, mirroring GameScreen's own pass-device gating.
    if (shoppers.size > 1 && shopperIndex != acknowledgedIndex) {
        PassDeviceScreen(
            nextPlayerName = shopper.name,
            onReady = { acknowledgedIndex = shopperIndex },
        )
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("${shopper.name}'s Shop", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Balance: ${viewModel.balanceFor(shopper.ownerId)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            for (weapon in WeaponCatalog.all) {
                // Baby Missile is always free and unlimited - never a purchasable row.
                if (weapon.type == WeaponType.BABY_MISSILE) continue
                WeaponShopRow(
                    displayName = weapon.displayName,
                    price = weapon.price,
                    owned = viewModel.ownedFor(shopper.ownerId, weapon.type),
                    ammoLimit = weapon.ammoLimit ?: 0,
                    canBuy = viewModel.canBuy(shopper.ownerId, weapon.type),
                    onBuy = { viewModel.buy(shopper.ownerId, weapon.type) },
                    onSell = { viewModel.sell(shopper.ownerId, weapon.type) },
                )
            }

            Button(
                onClick = { shopperIndex++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun WeaponShopRow(
    displayName: String,
    price: Int,
    owned: Int,
    ammoLimit: Int,
    canBuy: Boolean,
    onBuy: () -> Unit,
    onSell: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(displayName)
            Text("$price each - owned $owned/$ammoLimit", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSell, enabled = owned > 0) {
                Text("-")
            }
            Button(onClick = onBuy, enabled = canBuy, modifier = Modifier.padding(start = 8.dp)) {
                Text("+")
            }
        }
    }
}
