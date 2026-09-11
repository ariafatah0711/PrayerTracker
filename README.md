# 🕌 Prayer Tracker — Android Native (Offline-First)

Aplikasi pencatat dan pengingat waktu salat harian modern dengan arsitektur **Offline-First**, presisi astronomis tinggi, pengingat mengambang (Floating Overlay) di atas aplikasi lain, pelacakan Qadha, dan sinkronisasi data ke Google Drive & Google Sheets.

---

## 📋 Fitur Utama
1. **Perhitungan Waktu Salat 100% Offline**: Menggunakan algoritma astronomis mandiri tanpa memerlukan internet atau server VPS.
2. **Pengingat Mengambang (Floating Overlay)**: Modal dialog elegan yang muncul otomatis di atas aplikasi apapun (YouTube, WhatsApp, game, layar kunci) saat adzan tiba dengan 3 tombol wajib: `SUDAH SALAT`, `OTW (Siap-siap)`, dan `BELUM (Snooze)`.
3. **Sinkronisasi Ganda (Cloud & Offline)**:
   - **Google Cloud Sync**: Backup otomatis ke folder pribadi Google Drive dan sinkronisasi real-time ke spreadsheet Google Sheets.
   - **Ekspor Sat-Set (.CSV & .JSON)**: Ekspor riwayat salat ke file CSV (UTF-8 BOM) yang langsung bisa dibuka di Microsoft Excel, Google Sheets HP, atau dikirim ke WhatsApp tanpa setup apa pun.
4. **Alur Setup Bertahap (Step-by-Step Onboarding)**: Pilihan kota cerdas (termasuk Depok dan 70+ kota Indonesia), tombol deteksi GPS otomatis, dan konfirmasi ibadah hari ini.
5. **Pelacakan Qadha Otomatis**: Salat yang terlewat otomatis masuk ke tab Qadha untuk dilunasi kapan saja.

---

## 🛠️ Panduan Developer: Setup Google Cloud OAuth (Google Drive & Sheets Sync)

> [!IMPORTANT]
> **Mengapa Google Sync memerlukan setup ini?**
> Google Play Services mewajibkan sertifikat debug `SHA-1` aplikasi didaftarkan pada proyek Google Cloud Console untuk otorisasi keamanan OAuth 2.0. Tanpa pendaftaran ini, Google Play Services akan menolak login dengan kode `ApiException: 10 (DEVELOPER_ERROR)`.

Ikuti langkah mudah 5 menit berikut untuk mengaktifkan Google Cloud Sync:

### Langkah 1: Buka Google Cloud Console
1. Buka browser dan login ke [Google Cloud Console](https://console.cloud.google.com/).
2. Buat proyek baru (misalnya diberi nama `Prayer Tracker App`) atau pilih proyek yang sudah ada.

### Langkah 2: Aktifkan API Google Drive & Google Sheets
1. Di menu navigasi samping kiri, masuk ke **APIs & Services** -> **Enabled APIs & Services**.
2. Klik tombol **+ ENABLE APIS AND SERVICES** di bagian atas.
3. Cari dan aktifkan 2 API berikut:
   - **Google Drive API** -> Klik **Enable**.
   - **Google Sheets API** -> Klik **Enable**.

### Langkah 3: Konfigurasi Layar Persetujuan OAuth (OAuth Consent Screen)
1. Masuk ke menu **APIs & Services** -> **OAuth consent screen**.
2. Pilih User Type: **External**, lalu klik **Create**.
3. Isi informasi dasar:
   - **App name**: `Prayer Tracker`
   - **User support email**: Email Google kamu.
   - **Developer contact information**: Email Google kamu.
4. Klik **Save and Continue** sampai selesai.
5. Pada bagian **Test Users**, klik **Add Users** dan masukkan alamat email Google yang akan kamu gunakan untuk login di HP.

### Langkah 4: Buat OAuth Client ID (Android)
1. Masuk ke menu **APIs & Services** -> **Credentials**.
2. Klik **+ CREATE CREDENTIALS** di bagian atas -> Pilih **OAuth client ID**.
3. Pada dropdown **Application type**, pilih **Android**.
4. Masukkan data berikut secara persis:
   - **Name**: `Prayer Tracker Android Client`
   - **Package name**: 
     ```
     com.prayertracker.app
     ```
   - **SHA-1 certificate fingerprint**: 
     ```
     D0:BB:14:CA:F1:DB:BE:0D:B0:73:8E:30:B6:B1:AD:82:81:DD:E5:AF
     ```
5. Klik **Create**.

### Langkah 5: Hubungkan di Aplikasi
1. Tunggu sekitar 2 - 5 menit agar Google Play Services memperbarui data registrasi di server Google.
2. Buka aplikasi **Prayer Tracker** di HP kamu.
3. Masuk ke menu **Pengaturan** -> Tab **[ ☁️ Cloud & Cadangan ]**.
4. Tekan tombol **"Hubungkan Akun Google"** dan pilih email Google kamu.
5. Akun akan langsung terhubung, spreadsheet Google Sheets akan otomatis dibuat di Google Drive milikmu, dan statusnya menjadi **✓ Terhubung**!

---

## ⚡ Alternatif Instan (Tanpa Setup Developer)
Jika kamu tidak ingin repot mendaftar ke Google Cloud Console, kamu bisa menggunakan fitur cadangan instan yang sudah tersedia di tab **[ ☁️ Cloud & Cadangan ]**:
- **Tombol "📊 Buka di Google Sheets / Excel (.CSV)"**: Langsung membuat file CSV rapi yang bisa dibuka seketika di aplikasi Google Sheets HP kamu atau dibagikan ke WhatsApp.
- **Tombol "💾 Simpan Cadangan ke Google Drive / HP (.JSON)"**: Otomatis memunculkan opsi simpan ke Google Drive lewat menu Share bawaan Android.

---

## 💻 Cara Menjalankan & Kompilasi Proyek

### Kebutuhan Sistem
- Android Studio Ladybug / Koala atau yang lebih baru.
- JDK 17 atau JDK 21 (direkomendasikan JBR Android Studio).
- Android SDK API Level 34 (Android 14).

### Perintah Terminal (PowerShell di Windows)
```powershell
# Set JAVA_HOME ke JDK 21 / JBR Android Studio
$env:JAVA_HOME = "C:\Users\ariaf\.jdks\jbr-21.0.11" # Sesuaikan path JDK kamu

# Kompilasi kode Kotlin
.\gradlew.bat compileDebugKotlin

# Build file APK Debug
.\gradlew.bat assembleDebug

# Install langsung ke HP yang terhubung kabel USB
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

File APK siap pakai berada di:
`app\build\outputs\apk\debug\app-debug.apk`
