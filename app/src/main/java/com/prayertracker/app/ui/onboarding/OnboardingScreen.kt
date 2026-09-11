package com.prayertracker.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.prayertracker.app.ui.theme.*

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.detectGps(context) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Izin lokasi tidak diberikan", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SETUP PRAYER TRACKER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldLight,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Langkah ${state.currentStep + 1} dari 3",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val progress = when (state.currentStep) {
                    0 -> 0.33f
                    1 -> 0.66f
                    else -> 1.0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = EmeraldPrimary,
                    trackColor = SurfaceCardBorder
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val steps = listOf("1. Lokasi", "2. Cek Salat", "3. Selesai")
                    steps.forEachIndexed { index, label ->
                        val isCurrent = state.currentStep == index
                        val isDone = state.currentStep > index
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) EmeraldLight else if (isDone) TextPrimary else TextMuted
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = SurfaceDark,
                border = BorderStroke(0.5.dp, SurfaceCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.currentStep > 0) {
                        OutlinedButton(
                            onClick = { viewModel.goToPreviousStep() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SurfaceCardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⬅ Kembali")
                        }
                    }

                    if (state.currentStep < 2) {
                        Button(
                            onClick = { viewModel.goToNextStep() },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(if (state.currentStep > 0) 1.5f else 1f)
                        ) {
                            Text(
                                text = if (state.currentStep == 0) "Lanjut ke Cek Salat ➔" else "Lanjut ke Ringkasan ➔",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.finishOnboarding(onFinished) },
                            enabled = !state.isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menyimpan...", color = Color.White)
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✨ Mulai Gunakan", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (state.currentStep) {
                0 -> StepLocationContent(viewModel = viewModel)
                1 -> StepPrayersContent(viewModel = viewModel)
                2 -> StepSummaryContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun StepLocationContent(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.detectGps(context) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Izin lokasi tidak diberikan", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 18.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Pilih Lokasimu 🕌",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Jadwal adzan dihitung 100% offline secara presisi berdasarkan posisi lintang & bujur tempat tinggalmu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        // Active Selected Location Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, EmeraldLight),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(EmeraldContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = EmeraldLight,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KOTA AKTIF TERPILIH",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLight,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.selectedCity.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = if (state.selectedCity.province.isNotBlank())
                                "${state.selectedCity.province} • Koordinat: ${state.selectedCity.lat}, ${state.selectedCity.lng}"
                            else
                                "Koordinat: ${state.selectedCity.lat}, ${state.selectedCity.lng}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // Auto GPS Detect Button
        item {
            OutlinedButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasFine || hasCoarse) {
                        viewModel.detectGps(context) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isGpsDetecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = EmeraldLight, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mendeteksi Satelit GPS...", color = EmeraldLight)
                } else {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = EmeraldLight)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("📍 Deteksi Otomatis Lokasi Saya (GPS HP)", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Search Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Cari Kota Cepat:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = state.citySearchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Ketik nama kota (misal: Depok, Bogor, Bandung)...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (state.citySearchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldLight,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Quick Indonesian City Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Kota Populer Indonesia:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val popularList = listOf(
                        "Depok", "Jakarta", "Bogor", "Bekasi", "Tangerang", "Tangerang Selatan",
                        "Bandung", "Surabaya", "Semarang", "Yogyakarta", "Medan", "Makassar"
                    )
                    items(popularList) { cityName ->
                        val matched = viewModel.allCities.find { it.name.equals(cityName, ignoreCase = true) }
                        val isSelected = state.selectedCity.name.equals(cityName, ignoreCase = true)

                        Surface(
                            modifier = Modifier.clickable {
                                matched?.let { viewModel.onCitySelected(it) }
                            },
                            color = if (isSelected) EmeraldPrimary else SurfaceCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) EmeraldLight else SurfaceCardBorder)
                        ) {
                            Text(
                                text = cityName,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Filtered Cities List
        item {
            val filteredList = remember(state.selectedCountryCode, state.citySearchQuery) {
                if (state.citySearchQuery.isNotBlank()) {
                    viewModel.allCities.filter {
                        it.name.contains(state.citySearchQuery.trim(), ignoreCase = true) ||
                        it.province.contains(state.citySearchQuery.trim(), ignoreCase = true)
                    }
                } else {
                    viewModel.allCities.filter { it.countryCode == state.selectedCountryCode }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Daftar Kota (${filteredList.size} kota tersedia):",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )

                filteredList.take(30).forEach { city ->
                    val isSelected = state.selectedCity.name.equals(city.name, ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.onCitySelected(city) },
                        color = if (isSelected) SurfaceCard else SurfaceDark,
                        border = BorderStroke(1.dp, if (isSelected) EmeraldLight else SurfaceCardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = city.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) EmeraldLight else TextPrimary
                                )
                                if (city.province.isNotBlank()) {
                                    Text(
                                        text = city.province,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldLight)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPrayersContent(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 18.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Ibadah Hari Ini 📋",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sebelum membuka aplikasi, salat apa saja yang tadi sudah kamu kerjakan? Centang salat yang sudah selesai.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        // Info Alert Box
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmberGold.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Cara Kerja Pencatatan Awal:",
                            style = MaterialTheme.typography.labelLarge,
                            color = AmberGold,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "• ✅ Dicentang: Ditandai sebagai Selesai tepat waktu.\n• ⬜ Tidak Dicentang: Otomatis dimasukkan ke daftar Qadha sebagai pengingat utang salat yang belum dikerjakan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Action Buttons: Select All / Clear All
        if (state.pastPrayers.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Daftar Salat yang Lewat Hari Ini:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { viewModel.selectAllPrayers() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Centang Semua", style = MaterialTheme.typography.labelSmall, color = EmeraldLight)
                        }
                        TextButton(
                            onClick = { viewModel.clearAllPrayers() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Kosongkan", style = MaterialTheme.typography.labelSmall, color = StatusMissed)
                        }
                    }
                }
            }

            // Prayer Items Checkboxes (Managed locally in ViewModel, never resets)
            items(state.pastPrayers, key = { it.id }) { prayer ->
                val isChecked = state.checkedPrayerIds.contains(prayer.id)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.togglePrayerChecked(prayer.id) },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.5.dp,
                        if (isChecked) StatusCompleted.copy(alpha = 0.7f) else SurfaceCardBorder
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isChecked) SurfaceCard else SurfaceDark
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = prayer.effectiveDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Jadwal: ${prayer.formattedScheduledTime} WIB",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isChecked) "✓ Sudah dikerjakan (Selesai)" else "— Belum dikerjakan (Masuk Qadha)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isChecked) StatusCompleted else AmberGold
                            )
                        }

                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { viewModel.togglePrayerChecked(prayer.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = StatusCompleted,
                                uncheckedColor = TextMuted,
                                checkmarkColor = Color.White
                            )
                        )
                    }
                }
            }
        } else {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.5f)),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldLight,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Belum Ada Salat yang Lewat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Hari ini belum ada jadwal salat wajib yang berlalu. Kamu bisa langsung mulai tanpa memiliki riwayat salat terlewat hari ini!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepSummaryContent(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 18.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Alhamdulillah, Setup Selesai! 🎉",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Prayer Tracker telah siap menemani dan menjaga waktu ibadahmu setiap hari.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        // Summary Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SurfaceCardBorder),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "RINGKASAN PENGATURAN",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldLight
                    )

                    // 1. Lokasi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📍 Lokasi Salat", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(state.selectedCity.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                    // 2. Status Ibadah Hari Ini
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📋 Catatan Awal Hari Ini", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        val completedCount = state.checkedPrayerIds.size
                        val missedCount = state.pastPrayers.size - completedCount
                        Text(
                            "$completedCount Selesai, $missedCount Qadha",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (missedCount > 0) AmberGold else StatusCompleted
                        )
                    }

                    HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                    // 3. Salat Berikutnya
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🕒 Salat Berikutnya", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        val nextText = state.nextUpcomingPrayer?.let {
                            "${it.effectiveDisplayName} (${it.formattedScheduledTime})"
                        } ?: "Besok Subuh"
                        Text(nextText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = EmeraldLight)
                    }

                    HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                    // 4. Sistem Alarm & Offline
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔔 Pengingat Adzan", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("Aktif (AlarmManager)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = StatusCompleted)
                    }

                    HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔒 Penyimpanan", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("100% Offline Lokal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }
            }
        }
    }
}
