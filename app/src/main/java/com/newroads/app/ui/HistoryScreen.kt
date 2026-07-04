package com.newroads.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newroads.app.data.db.entity.WalkRecordEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История прогулок") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text("Пока нет завершённых прогулок", modifier = Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                items(history) { record -> WalkRecordRow(record) }
            }
        }
    }
}

@Composable
private fun WalkRecordRow(record: WalkRecordEntity) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(dateFormat.format(Date(record.startedAt)), style = MaterialTheme.typography.titleMedium)
            Text("Дистанция: ${"%.2f".format(record.distanceMeters / 1000)} км")
            Text("Шаги: ${record.steps}")
            Text("Новых дорог: ${"%.0f".format(record.newRoadFraction * 100)}%")
        }
    }
}
