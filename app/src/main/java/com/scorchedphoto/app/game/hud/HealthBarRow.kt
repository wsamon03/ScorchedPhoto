package com.scorchedphoto.app.game.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scorchedphoto.app.game.TankHudInfo
import com.scorchedphoto.engine.tanks.Tank

@Composable
fun HealthBarRow(tanks: List<TankHudInfo>, currentTankId: Int?, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        tanks.forEach { tank ->
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .width(64.dp)
                    .background(
                        if (tank.id == currentTankId) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tank.name,
                    color = if (tank.alive) Color(tank.color) else Color.Gray,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.DarkGray, RoundedCornerShape(2.dp)),
                ) {
                    val healthFraction = (tank.health.toFloat() / Tank.MAX_HEALTH).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(healthFraction)
                            .height(4.dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
