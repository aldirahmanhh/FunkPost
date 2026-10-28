# FunkPost

**Auto-poster dan auto-verifier Discord untuk Android.**

FunkPost membuat bot Discord memposting sesuai jadwal dan bisa menekan tombol verifikasi untuk
kamu, semua dari satu HP. Token dienkripsi di perangkat, tidak ada yang dikirim ke mana pun selain
ke Discord.

[English →](README.md)

---

## Fitur

| | |
|---|---|
| **Posting terjadwal** | Setiap channel berulang sesuai intervalnya sendiri, per bot, sampai kamu hentikan. |
| **Banyak bot** | Beberapa token bot berdampingan, masing-masing dengan daftar channel sendiri. Ganti bot aktif dengan satu ketukan. |
| **Verifikasi otomatis** | Memantau channel dengan token user dan menekan *Authenticate* / *Yes, Log Me In* hanya pada panel milik akun itu. |
| **Editor pesan** | Toolbar markdown Discord (tebal, miring, garis bawah, coret, spoiler, kode, kutipan, daftar, tautan) dengan pratinjau langsung. |
| **Riwayat aktivitas** | Setiap pengiriman, rate limit dan kegagalan dicatat dari yang terbaru, dibatasi agar tidak membengkak. |
| **Notifikasi** | Notifikasi status prioritas rendah plus peringatan prioritas tinggi saat token ditolak atau channel terus gagal. |
| **UI dua bahasa** | Inggris dan Bahasa Indonesia, bisa diganti di Setelan tanpa menutup aplikasi. |
| **Mode gelap** | Tombol manual di bilah atas, tersimpan setelah aplikasi ditutup. |
| **Aman saat reinstall** | Memasang build baru di atas yang lama tidak menghapus bot, channel, maupun riwayat. |

## Tampilan

Aplikasi punya lima tab:

- **Bot** — tambah bot, buka untuk mengelola channel-nya, mulai/hentikan posting.
- **Verifikasi** — akun yang memantau channel dan menekan tombol verifikasi.
- **Riwayat** — log kronologis, bisa difilter terkirim / masalah.
- **Setelan** — ganti nama bot aktif, ganti token, pilih bahasa, tutup pengingat optimasi baterai,
  tonton tutorial token.
- **Tentang** — fungsi aplikasi, janji privasi, kredit dan tautan.

## Keamanan

- **Token dienkripsi saat disimpan** dengan AES-256-GCM (Google Tink). Keyset dilindungi master key
  Android Keystore dan disimpan di `filesDir/keysets/`, bukan di database.
- **Tanpa telemetri.** Tidak ada analytics, crash reporting, atau SDK iklan. Satu-satunya lalu
  lintas jaringan adalah ke `discord.com`, dengan token yang kamu masukkan sendiri.
- **Backup dimatikan** (`allowBackup=false`, `dataExtractionRules` mengecualikan semuanya), jadi
  keyset tidak bisa keluar dari perangkat lewat ADB backup atau backup cloud.
- **Tidak auto-start.** Posting tidak pernah lanjut sendiri setelah reboot atau cold start; flag
  running direset dan kamu yang menekan Mulai.
- Token disamarkan di UI (`MTQ2…hV-E`); untuk melihatnya harus mengetuk secara sadar.

## Kebutuhan

| | |
|---|---|
| Android | 8.0 (API 26) ke atas |
| Build | JDK 17, Android SDK dengan platform 35 |
| Gradle | wrapper bawaan (8.9) |

## Build

```bash
git clone https://github.com/masfank/FunkPost.git
cd FunkPost
./gradlew :app:assembleDebug
```

APK dihasilkan di `app/build/outputs/apk/debug/app-debug.apk`.

Pasang ke perangkat yang terhubung:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build release

```bash
./gradlew :app:assembleRelease
```

Build release diperkecil dengan R8 dan resource shrinking. Kalau `key.properties` tidak ada, build
memakai debug key dengan peringatan, supaya APK sideload selalu bisa dipasang. Untuk tanda tangan
yang benar, buat `key.properties` di root proyek:

```properties
storeFile=/path/absolut/ke/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`key.properties` dan `*.jks` sudah diabaikan git.

## Pengujian

```bash
# Tes JVM: migrasi Room, kontrak repository, parser markdown (Robolectric + SQLite native)
./gradlew :app:testDebugUnitTest

# Tes di perangkat: enkripsi, secret at-rest, navigasi
./gradlew :app:connectedDebugAndroidTest
```

Suite instrumentasi menyentuh lapisan kripto, jadi ia memakai keystore yang sama dengan aplikasi.
`gradle.properties` menyetel `android.injected.androidTest.leaveApksInstalledAfterRun=true` agar
menjalankan tes tidak meng-uninstall aplikasi dan menghapus data di HP kamu.

Tes JVM yang penting:

- `PersistenceContractTest` — membangun database v1 asli dan membuktikan migrasi ke v3 tetap
  menyimpan semua bot dan channel; juga membuktikan mengedit channel tidak menghilangkan status
  terakhir dan id barisnya.
- `RoomRepositoryTest` — validasi repository dan perilaku hitungan channel.

## Arsitektur

Constructor injection biasa, tanpa framework DI. `AutoPostApplication` adalah composition root yang
menyediakan semua collaborator.

```
ui/          Layar Compose, satu ViewModel untuk kelima tab
 ├ channels/  pengelolaan bot + channel
 ├ verifiers/ akun verifikasi otomatis
 ├ logs/      riwayat aktivitas
 ├ settings/  setelan dan tentang
 ├ components/ widget bersama, renderer markdown, logo
 ├ i18n/      state bahasa dan helper t(en, id)
 └ theme/     warna, tipografi, ikon kustom
data/        Database Room, DAO, repository, preferensi DataStore
crypto/      cipher Tink AES-256-GCM di balik antarmuka SecretCipher
network/     klien REST Discord, WebSocket gateway, rate limiter
service/     foreground service, scheduler, mesin verifikasi
notify/      channel notifikasi dan peringatan yang bisa ditindak
```

**Penyimpanan.** Room 2.6.1, versi skema 3, dengan skema terekspor di `app/schemas/`. Migrasi
`1→2` (riwayat aktivitas) dan `2→3` (verifier) ditulis eksplisit; tidak ada fallback destruktif,
jadi proses upgrade tidak mungkin menghapus datamu.

**Penjadwalan.** `ChannelScheduler` menyimpan satu job per token yang berjalan, menyiapkan ulang
channel saat konfigurasinya berubah, dan hidup di scope coroutine level aplikasi sehingga
perpindahan layar tidak menghentikannya. Foreground service menjaga proses tetap hidup selama ada
yang berjalan.

**Bahasa.** `LanguageState` menyimpan bahasa aktif di `mutableStateOf` Compose, dan setiap teks
dihasilkan oleh `t("English", "Bahasa Indonesia")`. Pilihan yang tersimpan disalin dari ViewModel,
jadi mengganti bahasa langsung merekomposisi seluruh tampilan — termasuk baris status yang dibangun
ViewModel.

## Catatan konfigurasi

- **Optimasi baterai.** Android bisa menjeda pekerjaan latar belakang saat layar mati. Tab Setelan
  menyediakan kartu yang bisa ditutup untuk membuka layar pengecualian sistem; aplikasi tidak
  pernah mengecualikan dirinya sendiri secara diam-diam.
- **Izin notifikasi.** Di Android 13+ aplikasi meminta `POST_NOTIFICATIONS` saat pertama kali kamu
  menjalankan bot. Menolaknya tidak menghalangi posting; akan muncul banner yang menjelaskan.

## Kredit

Dibuat dan didesain oleh **MrFunk** — <https://tako.id/anrizz>

## Penafian

Gunakan ini di server milikmu atau yang boleh kamu otomatiskan. Token user bertindak sebagai
akunmu; kamu bertanggung jawab atas penggunaannya.
