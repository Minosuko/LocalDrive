# CloudDrive Sync for Android

Native Android 10+ client with a Material 3 interface for authenticated CloudDrive files, device and cloud media, SMS/MMS messaging, and incremental Wi-Fi backup and restore.

## App Sections

- **Home** shows device storage plus an individual storage card for every configured CloudDrive.
- **Medias** uses a top-right source menu for Device and CloudDrive images/videos, with a chronological library, folders, four display modes, image paging and zoom, video playback, and exact scroll-position return after closing a viewer. The current device's `Sync/<device>` backup is excluded from CloudDrive media results.
- **Files** switches between full Device storage and multiple named authenticated CloudDrive servers, with account-isolated cached listings.
- **Files** manages and disconnects CloudDrive 1, CloudDrive 2, and additional servers from the file browser.
- **Files** supports thumbnails, upload, file/folder creation, two-way device/cloud cut/copy/paste with progress, zoomable image viewing, video and audio playback, and CloudDrive Trash restore/permanent-delete/empty actions.
- **Files** renders cloud PSD, PSB, SAI, and SAI2 documents through the server preview API.
- **Messages** receives SMS/MMS notifications, resolves typed recipients to existing conversations, shows newly queued outgoing SMS immediately, and supports notification replies and mark-read actions.
- **Settings** shows a Sync card that opens a dedicated backup/restore window with destination, progress, and per-category controls.
- **Settings** manages the single root account attached to each CloudDrive and can re-authenticate a locked server.

## Open And Build

1. Open the `app/` directory in Android Studio.
2. Allow Android Studio to install Android SDK 35 and sync Gradle dependencies.
3. Run the `CloudDriveSync` configuration on an Android 10 or newer device.

On Windows, after installing Android SDK Platform 35, build from a terminal with:

```bat
build.bat debug
build.bat release
build.bat clean
```

`build.bat` downloads a private Gradle 8.9 distribution into `.gradle-dist/` on its first run when no Gradle wrapper is present. When `keystore.properties` is configured, release APKs are signed during the build.

## Configure

1. Start CloudDrive on the computer with `php router.php`.
2. Put the phone and computer on the same Wi-Fi network.
3. Open **Files > CloudDrive > overflow menu > Add CloudDrive** and enter `http://COMPUTER_IP:8080/CloudDrive`. Sign in as root, or use **Create root** for first-time server setup. Do not use `localhost`; on Android that refers to the phone.
4. Grant full device-file access for browsing and media-category access for syncing.
5. In **Settings > Sync**, configure automatic backup or use **Sync to CloudDrive** / **Sync to device**.

Every configured CloudDrive has exactly one root account and requires root sign-in before files, previews, storage details, or sync can be used. App traffic uses the authenticated `/api/mobile/v1` API and DAV mount. Access and refresh tokens are encrypted with Android Keystore. The same root credentials protect the web interface, Network Drive, and Media Device endpoints; mounted drives use HTTP Basic authentication.

Uploads are stored under:

```text
Sync/<manufacturer model>-<full device id>/media/<MediaStore relative path>
Sync/<manufacturer model>-<full device id>/downloads/<relative path>
```

The app remembers each media URI, modification time, size, and drive address. Unchanged files are skipped. Each CloudDrive has an independent sync history.

## Behavior

- Wi-Fi only, including local Wi-Fi without Internet access.
- Automatic sync defaults to daily at 00:00 and supports second, minute, hour, or day intervals.
- Manual foreground sync with progress notification.
- Sync destination folders are created before media scanning, including when no new media is found.
- Sync categories include Photos, Videos, Downloads, Contacts, SMS messages, and Call History.
- Contacts, messages, and calls are retained as content-addressed JSON snapshots; Downloads preserve subfolders.
- **Sync to CloudDrive** supports automatic schedules. **Sync to device** is manual-only and can restore a selected backup-device folder.
- Restored media returns directly to its saved shared-storage path, such as `DCIM/Camera`, `Photos/Messenger`, or `Videos/Messenger`; downloads return to the public Downloads directory.
- Streams files directly from MediaStore to WebDAV without loading whole files into memory.
- Supports Android 13 media-category permissions and Android 14 selected-media access.
- Uses cleartext HTTP for local LAN servers. Use HTTPS when exposing CloudDrive beyond a trusted LAN.
- Android treats SMS and Call History as restricted permissions. CloudDrive must hold the default SMS role to receive messages and restore SMS records.
