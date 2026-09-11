package com.prayertracker.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prayertracker.app.domain.calculation.CalculationMethod
import com.prayertracker.app.domain.calculation.Madhab
import com.prayertracker.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

    var cityMenuExpanded by remember { mutableStateOf(false) }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var madhabMenuExpanded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("🕌 Ibadah", "☁️ Cloud & Cadangan", "⚙️ Sistem")

    var settingsCountryCode by remember { mutableStateOf("ID") }
    var settingsCitySearch by remember { mutableStateOf("") }
    var isSettingsGpsDetecting by remember { mutableStateOf(false) }

    val settingsLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            isSettingsGpsDetecting = true
            viewModel.detectGpsLocation(context) { _, msg ->
                isSettingsGpsDetecting = false
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Izin lokasi tidak diberikan", Toast.LENGTH_SHORT).show()
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result.data)
    }

    val fileRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreFromJsonUri(context, it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(syncMessage) {
        syncMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark,
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Pengaturan",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Sesuaikan preferensi ibadah, notifikasi, dan sinkronisasi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            // Segmented Category Tabs for Lightweight & Smooth UI
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTab = index },
                            color = if (isSelected) EmeraldPrimary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            // TAB 1: CLOUD & CADANGAN
            if (selectedTab == 1) {
                // SECTION 1: GOOGLE DRIVE & SHEETS CLOUD SYNC
                item {
                    SettingsSection(title = "GOOGLE CLOUD SYNC (DRIVE & SHEETS)", icon = Icons.Default.CloudSync) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (!settings.isGoogleConnected) {
                                Text(
                                    text = "Hubungkan akun Google untuk backup otomatis ke Google Drive pribadi dan buat spreadsheet ibadah langsung di Google Sheets.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    lineHeight = 18.sp
                                )

                                Button(
                                    onClick = {
                                        googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Hubungkan Akun Google", fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                // Info Card: Developer SHA-1 Setup
                                Surface(
                                    color = SurfaceDark,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                tint = AmberGold,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Catatan Developer Setup (Google OAuth)",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = AmberGold,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Jika muncul Developer Error (10), daftarkan SHA-1 aplikasi di Google Cloud Console (Otorisasi OAuth 2.0). Panduan lengkap tersedia di README.md.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            lineHeight = 17.sp
                                        )

                                        val sha1String = "D0:BB:14:CA:F1:DB:BE:0D:B0:73:8E:30:B6:B1:AD:82:81:DD:E5:AF"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Package: com.prayertracker.app",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = "SHA-1 Debug Fingerprint",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("SHA1", sha1String)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(context, "SHA-1 berhasil disalin!", Toast.LENGTH_SHORT).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Salin SHA-1", style = MaterialTheme.typography.labelSmall, color = EmeraldLight)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "✓ Terhubung",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = StatusCompleted,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = settings.googleAccountEmail ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary
                                        )
                                        val lastSyncText = settings.lastSyncEpoch?.let {
                                            Instant.ofEpochMilli(it)
                                                .atZone(ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
                                        } ?: "Belum pernah"
                                        Text(
                                            text = "Terakhir Sync: $lastSyncText",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.disconnectGoogle() },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusMissed),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusMissed.copy(alpha = 0.5f))
                                    ) {
                                        Text("Putuskan", style = MaterialTheme.typography.bodySmall)
                                    }
                                }

                                HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                                // Action: Sync Now
                                Button(
                                    onClick = { viewModel.performSync() },
                                    enabled = !isSyncing,
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sedang Sinkronisasi...", color = Color.White)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sinkronkan Sekarang", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Action: Open Google Sheets in Browser
                                settings.spreadsheetUrl?.let { url ->
                                    OutlinedButton(
                                        onClick = {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(browserIntent)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberGold),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold.copy(alpha = 0.6f))
                                    ) {
                                        Icon(Icons.Default.TableChart, contentDescription = null, tint = AmberGold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buka Google Sheets Saya ↗", fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Action: Restore from Drive
                                OutlinedButton(
                                    onClick = { showRestoreDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = TextSecondary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Pulihkan Data dari Google Drive (Ganti HP)")
                                }
                            }
                        }
                    }
                }

                // SECTION 2: CADANGAN & EKSPOR FILE (OFFLINE & INSTAN)
                item {
                    SettingsSection(title = "EKSPOR & CADANGAN FILE (OFFLINE)", icon = Icons.Default.Save) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                text = "Ekspor atau cadangkan seluruh data salat kamu ke file tanpa perlu setup Google Cloud.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )

                            // 1. Ekspor ke Excel / CSV
                            Button(
                                onClick = {
                                    viewModel.exportToCsv(context) { chooserIntent ->
                                        context.startActivity(chooserIntent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.TableChart, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📊 Buka di Google Sheets / Excel (.CSV)", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Text(
                                text = "• File .CSV otomatis berformat UTF-8 BOM, bisa langsung dibuka rapi di Google Sheets, Microsoft Excel, atau dibagikan ke WhatsApp.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                            // 2. Backup ke JSON
                            Button(
                                onClick = {
                                    viewModel.exportBackupJson(context) { chooserIntent ->
                                        context.startActivity(chooserIntent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("💾 Simpan Cadangan ke Google Drive / HP (.JSON)", fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Text(
                                text = "• Otomatis muncul pilihan 'Simpan ke Google Drive' atau folder HP kamu agar data aman saat ganti HP.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                            // 3. Pulihkan Data
                            OutlinedButton(
                                onClick = {
                                    fileRestoreLauncher.launch("*/*")
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = EmeraldLight)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📥 Pulihkan Data dari File Cadangan (.JSON)")
                            }
                            Text(
                                text = "• Kembalikan seluruh data riwayat salat & qadha kapan pun dari file cadangan sebelumnya.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            } // end of if (selectedTab == 1)

        // TAB 0: IBADAH & NOTIFIKASI
        if (selectedTab == 0) {
            // 2. Lokasi & Kota
            item {
                SettingsSection(title = "LOKASI & KOORDINAT", icon = Icons.Default.LocationOn) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Current Location Info Card
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(EmeraldContainer, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(20.dp))
                                }
                                Column {
                                    Text(
                                        text = settings.cityName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Koordinat: ${settings.latitude}, ${settings.longitude}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Tombol Deteksi GPS Otomatis
                        Button(
                            onClick = {
                                val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasFine || hasCoarse) {
                                    isSettingsGpsDetecting = true
                                    viewModel.detectGpsLocation(context) { _, msg ->
                                        isSettingsGpsDetecting = false
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    settingsLocationPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSettingsGpsDetecting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Mendeteksi Posisi GPS...", color = Color.White)
                            } else {
                                Icon(Icons.Default.MyLocation, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📍 Deteksi Otomatis Lokasi GPS Saya", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        // 1. Pilih Negara
                        Text("1. Pilih Negara:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(viewModel.supportedCountries) { country ->
                                val isCountrySelected = country.code == settingsCountryCode
                                Surface(
                                    modifier = Modifier.clickable {
                                        settingsCountryCode = country.code
                                        settingsCitySearch = ""
                                    },
                                    color = if (isCountrySelected) EmeraldPrimary else SurfaceCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isCountrySelected) EmeraldLight else SurfaceCardBorder)
                                ) {
                                    Text(
                                        text = "${country.flag} ${country.name}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isCountrySelected) Color.White else TextSecondary,
                                        fontWeight = if (isCountrySelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // 2. Pencarian Kota
                        OutlinedTextField(
                            value = settingsCitySearch,
                            onValueChange = { settingsCitySearch = it },
                            placeholder = { Text("Cari kota (misal: Depok, Bogor, Tokyo...)", color = TextMuted, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (settingsCitySearch.isNotBlank()) {
                                    IconButton(onClick = { settingsCitySearch = "" }, modifier = Modifier.size(18.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldLight,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 3. Daftar Kota
                        val filteredCities = remember(settingsCountryCode, settingsCitySearch) {
                            if (settingsCitySearch.isNotBlank()) {
                                viewModel.allCities.filter {
                                    it.name.contains(settingsCitySearch.trim(), ignoreCase = true) ||
                                    it.province.contains(settingsCitySearch.trim(), ignoreCase = true)
                                }
                            } else {
                                viewModel.allCities.filter { it.countryCode == settingsCountryCode }
                            }
                        }

                        Text("Pilih dari ${filteredCities.size} kota tersedia:", style = MaterialTheme.typography.labelSmall, color = TextMuted)

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(filteredCities) { city ->
                                val isSelected = city.name.equals(settings.cityName, ignoreCase = true)
                                Surface(
                                    modifier = Modifier.clickable { viewModel.onCitySelected(city) },
                                    color = if (isSelected) EmeraldPrimary else SurfaceCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) EmeraldLight else SurfaceCardBorder)
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(
                                            text = city.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) Color.White else TextPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (city.province.isNotBlank()) {
                                            Text(
                                                text = city.province,
                                                fontSize = 10.sp,
                                                color = if (isSelected) Color.White.copy(alpha = 0.8f) else TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } // end of Lokasi item
        } // end of if (selectedTab == 0)

        // TAB 2: SISTEM & PENGINGAT
        if (selectedTab == 2) {
            // 3. Interval Reminder & OTW
            item {
                SettingsSection(title = "PENGINGAT & SNOOZE", icon = Icons.Default.Timer) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // OTW Interval
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Interval Pengingat OTW",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${settings.otwIntervalMinutes} Menit (Default: 3m)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StatusOtw,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = settings.otwIntervalMinutes.toFloat(),
                                onValueChange = { viewModel.onOtwIntervalChanged(it.toInt()) },
                                valueRange = 1f..10f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = StatusOtw,
                                    activeTrackColor = StatusOtw,
                                    inactiveTrackColor = SurfaceDark
                                )
                            )
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        // Snooze Interval
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Interval Pengingat setelah NO",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${settings.noSnoozeIntervalMinutes} Menit (Default: 10m)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AmberGold,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = settings.noSnoozeIntervalMinutes.toFloat(),
                                onValueChange = { viewModel.onNoIntervalChanged(it.toInt()) },
                                valueRange = 5f..30f,
                                steps = 4,
                                colors = SliderDefaults.colors(
                                    thumbColor = AmberGold,
                                    activeTrackColor = AmberGold,
                                    inactiveTrackColor = SurfaceDark
                                )
                            )
                        }
                    }
                }
            } // end of Pengingat & Snooze item
        } // end of if (selectedTab == 2)

        // TAB 0 CONTINUED: METODE, NOTIFIKASI & OVERLAY
        if (selectedTab == 0) {
            // 4. Metode Perhitungan Salat
            item {
                SettingsSection(title = "METODE KALKULASI", icon = Icons.Default.BrightnessAuto) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = methodMenuExpanded,
                            onExpandedChange = { methodMenuExpanded = !methodMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = settings.calculationMethod.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Metode Perhitungan", color = TextSecondary) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodMenuExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldLight,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = methodMenuExpanded,
                                onDismissRequest = { methodMenuExpanded = false }
                            ) {
                                CalculationMethod.entries.forEach { method ->
                                    DropdownMenuItem(
                                        text = { Text(method.displayName) },
                                        onClick = {
                                            viewModel.onCalculationMethodChanged(method)
                                            methodMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        ExposedDropdownMenuBox(
                            expanded = madhabMenuExpanded,
                            onExpandedChange = { madhabMenuExpanded = !madhabMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = settings.madhab.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Metode Ashar / Mazhab", color = TextSecondary) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = madhabMenuExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldLight,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = madhabMenuExpanded,
                                onDismissRequest = { madhabMenuExpanded = false }
                            ) {
                                Madhab.entries.forEach { madhab ->
                                    DropdownMenuItem(
                                        text = { Text(madhab.displayName) },
                                        onClick = {
                                            viewModel.onMadhabChanged(madhab)
                                            madhabMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Notifikasi
            item {
                SettingsSection(title = "NOTIFIKASI", icon = Icons.Default.Notifications) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "Aktifkan Notifikasi", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(text = "Heads-up reminder saat waktu masuk", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            Switch(
                                checked = settings.isNotificationEnabled,
                                onCheckedChange = {
                                    viewModel.onNotificationTogglesChanged(it, settings.isSoundEnabled, settings.isVibrationEnabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = EmeraldLight, checkedTrackColor = EmeraldContainer)
                            )
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Bunyi Adzan / Alarm", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = settings.isSoundEnabled,
                                onCheckedChange = {
                                    viewModel.onNotificationTogglesChanged(settings.isNotificationEnabled, it, settings.isVibrationEnabled)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = EmeraldLight, checkedTrackColor = EmeraldContainer)
                            )
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Vibrasi", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = settings.isVibrationEnabled,
                                onCheckedChange = {
                                    viewModel.onNotificationTogglesChanged(settings.isNotificationEnabled, settings.isSoundEnabled, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = EmeraldLight, checkedTrackColor = EmeraldContainer)
                            )
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        // Test Notification Button
                        Button(
                            onClick = { viewModel.triggerTestNotification() },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldContainer),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldLight)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🔔 Uji Notifikasi Masuk (Heads-Up)", color = EmeraldLight, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 6. Overlay Popup Melayang di Atas Aplikasi Lain
            item {
                val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true

                SettingsSection(title = "POPUP OVERLAY MELAYANG", icon = Icons.Default.Layers) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Muncul di Atas Semua Aplikasi",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Tampilkan dialog pop-up di tengah layar walau sedang membuka YouTube, WA, game, dll.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = settings.isOverlayEnabled,
                                onCheckedChange = { viewModel.updateOverlayEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = EmeraldLight,
                                    checkedTrackColor = EmeraldContainer
                                )
                            )
                        }

                        Divider(color = SurfaceCardBorder, thickness = 0.5.dp)

                        // Permission Status Banner
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (hasOverlayPermission) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StatusCompletedBg.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusCompleted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Izin 'Tampil di atas aplikasi lain' AKTIF",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusCompleted,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StatusPendingBg.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = AmberGold,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Izin Android Diperlukan",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AmberGold,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Agar pop-up bisa melayang di atas aplikasi lain saat jam salat, izinkan 'Display over other apps' di HP kamu.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Buka Pengaturan Izin HP", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Test Overlay Button (Immediate)
                        OutlinedButton(
                            onClick = {
                                val intent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
                                    context = context,
                                    prayerId = "test_preview_id",
                                    prayerName = "Maghrib (Uji Coba)",
                                    timeFormatted = "18:05 WIB",
                                    notificationId = 9998,
                                    alertType = "ENTRY"
                                )
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.8f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = EmeraldLight)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("👁️ Uji Tampilan Popup Langsung", color = EmeraldLight, fontWeight = FontWeight.Bold)
                        }

                        // Test Overlay Over Other Apps (5-Second Delay)
                        Button(
                            onClick = {
                                Toast.makeText(
                                    context,
                                    "⏰ Segera tekan HOME & buka WA/YouTube! Popup akan melayang dalam 5 detik.",
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.triggerDelayedOverlayTest(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🚀 Uji Melayang di Luar App (5 Detik)", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } // end of Overlay item
        } // end of if (selectedTab == 0)

        // TAB 2 CONTINUED: RESET & PEMBERSIHAN DATA
        if (selectedTab == 2) {
            // 7. Reset & Pembersihan Data
            item {
                SettingsSection(title = "RESET & PEMBERSIHAN DATA", icon = Icons.Default.DeleteForever) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Jika kamu baru memasang aplikasi atau ingin memulai catatan bersih dari waktu sekarang (tanpa salat tadi siang dianggap terlewat), kamu bisa mereset seluruh data di sini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusMissed),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusMissed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusMissed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset Semua Data (Mulai dari Nol)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        }

        // Reset Confirmation Dialog
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Reset Semua Catatan Salat?", color = StatusMissed, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Semua riwayat salat dan daftar qadha sebelumnya akan dihapus bersih. Pelacakan salat akan dimulai segar terhitung mulai detik ini.",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetDialog = false
                            viewModel.resetAllData {
                                android.widget.Toast.makeText(context, "Data berhasil di-reset. Membuka ulang aplikasi...", android.widget.Toast.LENGTH_SHORT).show()
                                val restartIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                if (restartIntent != null) {
                                    context.startActivity(restartIntent)
                                }
                                (context as? Activity)?.finish()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusMissed)
                    ) {
                        Text("Ya, Reset Bersih", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }

        // Restore Confirmation Dialog
        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Pulihkan Data dari Google Drive?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Data catatan salat dari Google Drive pribadi kamu akan diimpor dan digabungkan ke database lokal ponsel ini.",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRestoreDialog = false
                            viewModel.performRestore()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Text("Pulihkan Sekarang", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreDialog = false }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AmberGold,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberGold,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
            }
            content()
        }
    }
}
