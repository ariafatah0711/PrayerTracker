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
    val showSyncChoiceDialog by viewModel.showSyncChoiceDialog.collectAsState()
    val showTrashCloudConfirmDialog by viewModel.showTrashCloudConfirmDialog.collectAsState()

    var cityMenuExpanded by remember { mutableStateOf(false) }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var madhabMenuExpanded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showConfirmUploadToCloudDialog by remember { mutableStateOf(false) }
    var showConfirmPullFromCloudDialog by remember { mutableStateOf(false) }
    var showSha1Info by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Ibadah", "Cadangan", "Sistem")

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
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 2.dp)) {
                    Text(
                        text = "Pengaturan",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Preferensi ibadah, cadangan, dan sistem",
                        style = MaterialTheme.typography.bodySmall,
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
                        .padding(3.dp),
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
                                modifier = Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ==========================================
            // TAB 0: IBADAH
            // ==========================================
            if (selectedTab == 0) {
                // 1. Lokasi & Koordinat
                item {
                    SettingsSection(title = "Lokasi & Koordinat", icon = Icons.Default.LocationOn) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                color = SurfaceDark,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(EmeraldContainer, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
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

                            // GPS Auto Detect Button
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
                                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Deteksi Lokasi Otomatis (GPS)", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                            // Pilih Negara
                            Text("Pilih Negara:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
                                        color = if (isCountrySelected) EmeraldPrimary else SurfaceDark,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isCountrySelected) EmeraldLight else SurfaceCardBorder)
                                    ) {
                                        Text(
                                            text = "${country.flag} ${country.name}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 12.sp,
                                            color = if (isCountrySelected) Color.White else TextSecondary,
                                            fontWeight = if (isCountrySelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            // Pencarian Kota
                            OutlinedTextField(
                                value = settingsCitySearch,
                                onValueChange = { settingsCitySearch = it },
                                placeholder = { Text("Cari kota...", color = TextMuted, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                                trailingIcon = {
                                    if (settingsCitySearch.isNotBlank()) {
                                        IconButton(onClick = { settingsCitySearch = "" }, modifier = Modifier.size(16.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldLight,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Daftar Kota
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

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(filteredCities) { city ->
                                    val isSelected = city.name.equals(settings.cityName, ignoreCase = true)
                                    Surface(
                                        modifier = Modifier.clickable { viewModel.onCitySelected(city) },
                                        color = if (isSelected) EmeraldPrimary else SurfaceDark,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) EmeraldLight else SurfaceCardBorder)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                            Text(
                                                text = city.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else TextPrimary
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
                }

                // 2. Metode Perhitungan
                item {
                    SettingsSection(title = "Metode Perhitungan", icon = Icons.Default.Calculate) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    shape = RoundedCornerShape(8.dp),
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
                                    shape = RoundedCornerShape(8.dp),
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
            } // end of if (selectedTab == 0)

            // ==========================================
            // TAB 1: CADANGAN
            // ==========================================
            if (selectedTab == 1) {
                // 1. Google Drive & Sheets Cloud Sync
                item {
                    SettingsSection(title = "Sinkronisasi Google (Drive & Sheets)", icon = Icons.Default.CloudSync) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!settings.isGoogleConnected) {
                                Text(
                                    text = "Hubungkan Google untuk sinkronisasi otomatis riwayat salat ke Google Drive pribadi dan Google Sheets.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    lineHeight = 17.sp
                                )

                                Button(
                                    onClick = {
                                        googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Hubungkan Akun Google", fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                // Collapsible Developer SHA-1 note
                                TextButton(
                                    onClick = { showSha1Info = !showSha1Info },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        if (showSha1Info) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = AmberGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (showSha1Info) "Sembunyikan Bantuan OAuth (SHA-1)" else "Bantuan Setup OAuth (SHA-1 Developer)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberGold
                                    )
                                }

                                if (showSha1Info) {
                                    Surface(
                                        color = SurfaceDark,
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Jika Developer Error (10), daftarkan SHA-1 aplikasi di Google Cloud Console:",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                            val sha1String = "D0:BB:14:CA:F1:DB:BE:0D:B0:73:8E:30:B6:B1:AD:82:81:DD:E5:AF"
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "SHA-1: ${sha1String.take(17)}...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                TextButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        val clip = android.content.ClipData.newPlainText("SHA1", sha1String)
                                                        clipboard.setPrimaryClip(clip)
                                                        Toast.makeText(context, "SHA-1 disalin!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Salin SHA-1", style = MaterialTheme.typography.labelSmall, color = EmeraldLight)
                                                }
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
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )
                                        val lastSyncText = settings.lastSyncEpoch?.let {
                                            Instant.ofEpochMilli(it)
                                                .atZone(ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
                                        } ?: "Belum pernah"
                                        Text(
                                            text = "Sync: $lastSyncText",
                                            style = MaterialTheme.typography.labelSmall,
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
                                // Action 1: Unggah Data HP ke Cloud
                                Button(
                                    onClick = { showConfirmUploadToCloudDialog = true },
                                    enabled = !isSyncing,
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sedang Sinkronisasi...", color = Color.White)
                                    } else {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Unggah Data HP ke Cloud", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Action 2: Tarik Data dari Cloud ke HP
                                OutlinedButton(
                                    onClick = { showConfirmPullFromCloudDialog = true },
                                    enabled = !isSyncing,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldLight),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Tarik Data dari Cloud (Download ke HP)", fontWeight = FontWeight.SemiBold)
                                }

                                // Action 3: Buka Google Sheets
                                settings.spreadsheetUrl?.let { url ->
                                    OutlinedButton(
                                        onClick = {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(browserIntent)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberGold),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold.copy(alpha = 0.6f))
                                    ) {
                                        Icon(Icons.Default.TableChart, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Buka Google Sheets ↗", fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Action 4: Pindahkan Cadangan ke Sampah
                                OutlinedButton(
                                    onClick = { viewModel.openTrashCloudConfirmDialog() },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusMissed),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusMissed.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusMissed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Pindahkan Cadangan Cloud ke Sampah", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // 2. Cadangan File Lokal
                item {
                    SettingsSection(title = "Cadangan File", icon = Icons.Default.Save) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Ekspor CSV
                            Button(
                                onClick = {
                                    viewModel.exportToCsv(context) { chooserIntent ->
                                        context.startActivity(chooserIntent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.TableChart, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ekspor ke CSV (Excel / Sheets)", fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            // Cadangkan JSON
                            Button(
                                onClick = {
                                    viewModel.exportBackupJson(context) { chooserIntent ->
                                        context.startActivity(chooserIntent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cadangkan ke File JSON", fontWeight = FontWeight.Bold, color = Color.Black)
                            }

                            // Pulihkan JSON
                            OutlinedButton(
                                onClick = {
                                    fileRestoreLauncher.launch("*/*")
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Pulihkan dari File JSON")
                            }
                        }
                    }
                }
            } // end of if (selectedTab == 1)

            // ==========================================
            // TAB 2: SISTEM
            // ==========================================
            if (selectedTab == 2) {
                // 1. Notifikasi & Suara
                item {
                    SettingsSection(title = "Notifikasi & Suara", icon = Icons.Default.Notifications) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = "Aktifkan Notifikasi", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    Text(text = "Pengingat saat waktu salat masuk", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                                Switch(
                                    checked = settings.isNotificationEnabled,
                                    onCheckedChange = {
                                        viewModel.onNotificationTogglesChanged(it, settings.isSoundEnabled, settings.isVibrationEnabled)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = EmeraldLight, checkedTrackColor = EmeraldContainer)
                                )
                            }

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

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

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

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

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                            Button(
                                onClick = { viewModel.triggerTestNotification() },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldContainer),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Uji Notifikasi (Heads-Up)", color = EmeraldLight, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 2. Popup Melayang (Overlay)
                item {
                    val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(context)
                    } else true

                    SettingsSection(title = "Popup Melayang (Overlay)", icon = Icons.Default.Layers) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Muncul di Atas Aplikasi Lain",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Tampilkan pop-up konfirmasi saat membuka app lain",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = settings.isOverlayEnabled,
                                    onCheckedChange = { viewModel.updateOverlayEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = EmeraldLight,
                                        checkedTrackColor = EmeraldContainer
                                    )
                                )
                            }

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (hasOverlayPermission) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(StatusCompletedBg.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = StatusCompleted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Izin 'Tampil di atas aplikasi lain' AKTIF",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusCompleted,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(StatusPendingBg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = AmberGold,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Izin Android Diperlukan",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AmberGold,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Izinkan 'Display over other apps' agar popup bisa melayang saat jam salat tiba.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
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
                                            Text("Buka Pengaturan Izin HP", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

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
                                Icon(Icons.Default.Layers, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Uji Tampilan Popup Langsung", color = EmeraldLight, fontWeight = FontWeight.Bold)
                            }

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
                                Icon(Icons.Default.Timer, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Uji Melayang di Luar App (5 Detik)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 3. Pengingat & Snooze
                item {
                    SettingsSection(title = "Pengingat & Snooze", icon = Icons.Default.Timer) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        text = "${settings.otwIntervalMinutes} Menit",
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

                            HorizontalDivider(color = SurfaceCardBorder, thickness = 0.5.dp)

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
                                        text = "${settings.noSnoozeIntervalMinutes} Menit",
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
                }

                // 4. Reset & Pembersihan Data
                item {
                    SettingsSection(title = "Reset & Pembersihan Data", icon = Icons.Default.DeleteForever) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Mulai catatan bersih dari waktu sekarang dan reset seluruh riwayat salat & qadha.",
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
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusMissed, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reset Semua Data (Mulai dari Nol)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } // end of if (selectedTab == 2)
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

        // Dialog Pilihan Sumber Data Saat Pertama Login
        if (showSyncChoiceDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissSyncChoiceDialog() },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(EmeraldContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(20.dp))
                        }
                        Text("Pilih Sumber Data Utama", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Akun Google kamu berhasil terhubung! Silakan tentukan data mana yang ingin kamu gunakan:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        // Opsi 1: Gunakan Data HP Ini (Upload ke Cloud)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onSelectSyncLocalToCloud() }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).background(EmeraldContainer, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(20.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Gunakan Data HP Ini", fontWeight = FontWeight.Bold, color = EmeraldLight, style = MaterialTheme.typography.titleSmall)
                                        Surface(shape = RoundedCornerShape(4.dp), color = EmeraldContainer) {
                                            Text("Upload", style = MaterialTheme.typography.labelSmall, color = EmeraldLight, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Unggah catatan yang ada di HP ini untuk membuat spreadsheet & cadangan baru di Google Drive kamu.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }

                        // Opsi 2: Gunakan Data dari Cloud (Download ke HP)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onSelectSyncCloudToLocal() }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).background(AmberGold.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AmberGold, modifier = Modifier.size(20.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Gunakan Data dari Cloud", fontWeight = FontWeight.Bold, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                                        Surface(shape = RoundedCornerShape(4.dp), color = AmberGold.copy(alpha = 0.15f)) {
                                            Text("Download", style = MaterialTheme.typography.labelSmall, color = AmberGold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Tarik & pulihkan data salat yang sudah ada di Google Drive / Sheets ke HP ini.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissSyncChoiceDialog() }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }

        // Dialog Konfirmasi Pindah ke Sampah
        if (showTrashCloudConfirmDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissTrashCloudConfirmDialog() },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusMissed, modifier = Modifier.size(22.dp))
                        Text("Pindahkan ke Sampah?", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Cadangan di Google Drive (prayer_tracker_backup.json) dan Google Spreadsheet akan dipindahkan ke folder Sampah (Trash) di Google Drive kamu.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "✓ File masih bisa dipulihkan dari Sampah Google Drive jika kamu berubah pikiran.\n✓ Jika kamu menyinkronkan lagi nanti, aplikasi akan otomatis membuat cadangan baru yang segar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberGold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmTrashCloudData() },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusMissed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Pindahkan ke Sampah", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissTrashCloudConfirmDialog() }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }

        // Dialog Konfirmasi Unggah Data HP ke Cloud
        if (showConfirmUploadToCloudDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmUploadToCloudDialog = false },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(EmeraldContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(20.dp))
                        }
                        Text("Unggah Data ke Cloud?", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Catatan salat dari HP ini akan diunggah untuk memperbarui Google Drive dan Google Sheets kamu.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "✓ Data di cloud akan diselaraskan dengan catatan lokal saat ini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldLight
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmUploadToCloudDialog = false
                            viewModel.onSelectSyncLocalToCloud()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Ya, Unggah Sekarang", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmUploadToCloudDialog = false }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }

        // Dialog Konfirmasi Tarik Data dari Cloud ke HP
        if (showConfirmPullFromCloudDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmPullFromCloudDialog = false },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(AmberGold.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AmberGold, modifier = Modifier.size(20.dp))
                        }
                        Text("Tarik Data dari Cloud?", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Catatan salat dari Google Drive & Google Sheets kamu akan diunduh dan dipulihkan ke HP ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "✓ Gunakan opsi ini jika kamu baru saja mengedit spreadsheet atau ingin memulihkan riwayat salat ke HP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberGold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmPullFromCloudDialog = false
                            viewModel.onSelectSyncCloudToLocal()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Ya, Tarik Data", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmPullFromCloudDialog = false }) {
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
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AmberGold,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AmberGold,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}
