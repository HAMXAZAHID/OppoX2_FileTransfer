# Home Files for OPPO Watch X2

An app for the OPPO Watch X2 that reaches the shared folders on your home network over SMB (Windows file sharing, Synology/QNAP and most other NAS boxes, Samba on Linux or a Raspberry Pi).

It is built for the **Chinese OPPO Watch X2 (ColorOS Watch, plain Android 11)**: it needs no Google Play services, no Wear OS and no Google libraries, and it also runs on Wear OS watches. CI checks the APK's manifest for anything Google- or Wear-only on every build.

On the watch you can:

- save one or more servers (address, share, username, password; passwords are encrypted with the Android Keystore)
- browse folders with the touch screen or the crown
- download files to the watch's Download folder, with progress and a stop button
- upload files from the watch into the folder you're looking at (name clashes get "(1)" added instead of overwriting)
- create folders, delete files and folders on the server
- open or delete downloaded files on the watch

While it's open, the app asks for Wi-Fi and sends its traffic over it, because the Bluetooth link through your phone can't reach your home network and is very slow.

## Install on the watch (ColorOS Watch)

1. On GitHub, open the **Actions** tab, pick the latest green **Build APK** run, and download the `HomeFiles-apk` artifact. Unzip it and use **`HomeFiles-release.apk`** (not the debug one).
2. On the watch: Settings > About watch > Version info, tap the version number until developer options unlock. Turn on **USB debugging**.
3. Connect the watch to your PC with its USB cable, choose file transfer, and accept the debugging prompt on the watch.
4. On the PC, with [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) installed:
   ```
   adb devices
   adb install HomeFiles-release.apk
   ```

### Let it save into the Download folder

Downloads go to the watch's shared **Download** folder (`/storage/emulated/0/Download`), where file managers, other apps and `adb pull` can reach them. Android 11 calls this "All files access". Grant it once from the PC:

```
adb shell appops set dev.hamza.watchfiles MANAGE_EXTERNAL_STORAGE allow
```

(or tap **Allow Downloads access** in the app if your watch has that settings screen). Until it's granted, downloads go to the app's private folder instead.

### Adding a server without typing on the watch

With the watch connected over adb, add a server from the PC in one command:

```
adb shell am start -n dev.hamza.watchfiles/.ImportActivity --es host 192.168.1.10 --es share Media --es user me --es pass secret --es name MyPC
```

Leave out `--es user` and `--es pass` for guest access, and add `--ei port 4455` for a non-standard port. Running it again with the same host and share updates that server. Only adb can do this: normal apps on the watch aren't allowed to open this screen. You can also fill any text box from the PC with `adb shell input text "192.168.1.10"` while the box is focused.

## Set up a share

- **Windows PC:** right-click a folder > Properties > Sharing > Share, add your account. Find the PC's IP with `ipconfig`. On the watch, use the IP, the share name, and your Windows username and password (for a Microsoft account, use the account email and its password).
- **NAS:** turn on SMB / Windows file service, then use the NAS IP, the shared folder name, and a NAS user.

The address field also accepts `\\PC\Share`, `smb://nas/Media` or `nas:4455`, and the username field accepts `DOMAIN\user`.

## Project layout

- `smbcore/` plain Kotlin/JVM SMB client wrapper (built on [smbj](https://github.com/hierynomus/smbj)), with unit tests and integration tests against a real Samba server
- `app/` the Wear OS app (Jetpack Compose for Wear OS)
- `.github/workflows/build.yml` runs the Samba tests and builds the debug and release APKs on every push

Run the core tests locally without the Android SDK:

```
SMB_TEST_HOST=127.0.0.1 SMB_TEST_PORT=4455 SMB_TEST_USER=smbuser SMB_TEST_PASS=testpass SMB_TEST_SHARE=test \
  ./gradlew -PcoreOnly :smbcore:test
```
