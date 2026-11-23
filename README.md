# Home Files for OPPO Watch X2

A Wear OS app that reaches the shared folders on your home network over SMB (Windows file sharing, Synology/QNAP and most other NAS boxes, Samba on Linux or a Raspberry Pi).

On the watch you can:

- save one or more servers (address, share, username, password; passwords are encrypted with the Android Keystore)
- browse folders with the touch screen or the crown
- download files to the watch, with progress and a stop button
- upload files from the watch into the folder you're looking at (name clashes get "(1)" added instead of overwriting)
- create folders, delete files and folders on the server
- open or delete downloaded files on the watch

The app asks Wear OS for Wi-Fi while it's open, because the normal Bluetooth link through your phone can't reach your home network and is very slow.

## Install

1. Open the **Actions** tab on GitHub, pick the latest green **Build APK** run, and download the `HomeFiles-apk` artifact. Unzip it to get `HomeFiles-release.apk`.
2. On the watch: Settings > System > About > tap **Build number** 7 times to unlock Developer options. Then in Developer options turn on **ADB debugging** and **Wireless debugging**, and note the IP address and port shown (pair first if asked).
3. On your PC (with [platform-tools](https://developer.android.com/tools/releases/platform-tools)):
   ```
   adb pair <watch-ip>:<pairing-port>     # only the first time, enter the pairing code
   adb connect <watch-ip>:<port>
   adb install HomeFiles-release.apk
   ```

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
