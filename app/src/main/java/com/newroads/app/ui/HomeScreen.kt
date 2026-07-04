package com.newroads.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRouteReady: () -> Unit,
    onOpenHistory: () -> Unit,
    onPickLocation: () -> Unit
) {
    val targetSteps by viewModel.targetSteps.collectAsState()
    val route by viewModel.route.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val startPoint by viewModel.startPoint.collectAsState()
    var stepsText by remember(targetSteps) { mutableStateOf(targetSteps.toString()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Новые дороги") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = stepsText,
                onValueChange = { text ->
                    stepsText = text
                    text.toIntOrNull()?.let(viewModel::setTargetSteps)
                },
                label = { Text("Цель по шагам") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = startPoint?.let { "Старт: ${formatDecimal(it.lat, 5)}, ${formatDecimal(it.lon, 5)}" }
                    ?: "Старт: текущее местоположение",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Button(onClick = onPickLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать точку старта на карте")
            }

            if (startPoint != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { viewModel.setStartPoint(null) }) {
                    Text("Использовать текущее местоположение")
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.generateRoute() },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сгенерировать маршрут")
            }

            if (isLoading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(statusMessage ?: "Строим маршрут...")
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            route?.let { r ->
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Длина: ${formatDecimal(r.distanceMeters / 1000, 2)} км")
                        Text("Примерно шагов: ${r.estimatedSteps}")
                        Text("Новых дорог в маршруте: ${formatDecimal(r.newRoadFraction * 100, 0)}%")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRouteReady, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать на карте и идти")
                }
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onOpenHistory) {
                Text("История прогулок")
            }
        }
    }
}

private fun formatDecimal(value: Double, digits: Int): String = "%.${digits}f".format(value)
