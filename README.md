<p align="center">
  <img src="https://vrotek.com/assets/logo.svg?v=1.0" alt="VROTEK" width="80" height="80">
</p>

<h1 align="center">Kopp TV</h1>

<p align="center">
  <strong>A small Android TV launcher that gets you to your apps.</strong>
</p>

<p align="center">
  <a href="#build">Build</a> •
  <a href="#install">Install</a> •
  <a href="#android-optimisation">Optimisation</a> •
  <a href="#tv-setup">TV setup</a> •
  <a href="#configure-home">Configure Home</a> •
  <a href="#photos">Photos</a> •
  <a href="#development">Development</a>
</p>

---

Choose the apps you want on Home, put them in order and get on with watching. Kopp TV uses native Android views, local photos and an optional clock and weather line. It has no external app libraries, ads or accounts.

One to five apps form a centered row. Six fill the row, and larger selections scroll horizontally. Rounded banners have a thin selection ring without zoom or layout movement. Press **Left / Right** to select an app, **OK** to open it and **Up** for the menu.

## Build

You need JDK 17 and an Android SDK with Android 35 and Build Tools 34.0.0. Set `ANDROID_HOME` or use an untracked `local.properties` file for the SDK location.

```sh
git clone https://github.com/teamvrotek/kopp-tv.git
cd kopp-tv
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Windows, use `gradlew.bat`. The first build downloads Gradle and build dependencies.

The project pins Gradle 8.9 and Android Gradle Plugin 8.7.3. App source and bytecode use Java 8, with API 26 minimum and API 35 target. Application ID: `ee.kalle.minimaltv`.

For an unsigned release build, run `./gradlew assembleRelease` and sign it separately. Keep keys outside the repository. Updates need the same signing key as the installed app; a different debug key cannot replace an existing installation.

## Install

Install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) and put `adb` on your command path. For wireless installation on Android TV 14, connect the computer and TV to the same local network.

1. Open **Settings → System → About → Android TV OS build** on the TV and press OK seven times. The **Android TV OS version** row opens the Android Easter egg.
2. Open **System → Developer options → Wireless debugging**, enable it and choose **Pair new device**.
3. Pair using the IP address and port shown in that dialog:

```sh
adb pair TV_IP:PAIRING_PORT
```

Enter the code when prompted. Return to the main Wireless debugging screen and connect using its debugging port:

```sh
adb connect TV_IP:DEBUG_PORT
adb devices
```

Replace the placeholders with your TV's values. Pairing and debugging use different ports. Continue when the TV is listed with state `device`. See [Android's wireless ADB guide](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ee.kalle.minimaltv/.MainActivity
```

If several devices are connected, add `-s TV_IP:DEBUG_PORT` after `adb` in every command.

## Android optimisation

Open **Up → Settings → Android optimisation** (**Androidi optimeerimine**). **Scan** reads performance and privacy settings, memory, storage and supported Android diagnostics. Review the report, then choose **Optimise** to apply its proposed changes. Optional app findings open **App settings** for review; the optimiser does not disable or uninstall apps.

Enable system access once from an authorized computer, after installing Kopp TV:

```sh
python3 scripts/enable_optimizer.py --serial TV_IP:DEBUG_PORT --apply
```

This Python 3.8+ command grants only `WRITE_SECURE_SETTINGS` for settings changes and `DUMP` for supported system diagnostics. It checks the installed app and its declared permissions, preserves existing grants and saves their previous states in the ignored `.kopp-tv/` directory before changing anything. Without `--apply`, it only previews. Use `--status` to inspect permission states. Multiple connected devices require `--serial`.

You can disconnect ADB and turn off Wireless debugging afterward. The in-app controls use the granted permissions without root or a separate background helper.

The preview can propose these changes when the corresponding settings are available:

- Turn off Android animations and the idle screensaver, use a normal ten-minute idle timeout, and disable staying awake while plugged in.
- Disable Android location access for apps, plus Wi-Fi and Bluetooth scanning while those radios are switched off. Normal network and Bluetooth connections remain enabled. Features that need Android location may stop working; Kopp TV's manually chosen weather city still works.
- Clear the default assistant and voice interaction service. This disables the default voice-assistant path and can stop the remote's Assistant button from working. It does not revoke microphone access from other apps, and system role changes or firmware may select an assistant again.

Scan data stays on the TV, and the original-setting journal is saved in the app's private storage. There are no diagnostic uploads. Repeated optimisation keeps the saved original values. **Restore saved settings** restores those values; failed changes attempt rollback and report incomplete recovery. Uninstalling Kopp TV deletes its journal, so restore first if you want to undo the settings changes.

Streaming services can still observe IP addresses and account activity. The report links to Android privacy settings for microphone and camera permissions, Usage & diagnostics, Ads and account controls. These need separate review. See Google's guidance on [Google TV advertising IDs](https://support.google.com/googletv/answer/13392198?hl=en) and [Cast usage reports](https://support.google.com/chromecast/answer/6279421?hl=en).

To remove the permission grants added by the computer setup, first restore any TV settings you want to undo in Kopp TV, then use the permission backup filename printed during setup:

```sh
python3 scripts/enable_optimizer.py --serial TV_IP:DEBUG_PORT --restore .kopp-tv/optimizer-BACKUP.json --apply
```

Permission restore checks the device, Android user and backup format. It revokes only grants added by that setup, preserves pre-existing grants, and stops if a pre-existing grant changed independently. Restoring permissions leaves Android setting values as they are.

## TV setup

The optional Python 3.8+ setup script configures Home and idle behavior in one command:

```sh
python3 scripts/configure_tv.py --serial TV_IP:DEBUG_PORT --apply
```

It saves the current settings before changing anything, opens Kopp TV, enables its Home-button service alongside existing accessibility services, turns off Android animations and the Android idle screensaver, and sets normal screen-off and sleep timeouts to ten minutes. It also disables the stay-awake-while-plugged-in setting. Kopp TV must already be installed.

Without `--apply`, the script only reads and previews changes. Use `--status` to inspect the current values. Backups are saved in the ignored `.kopp-tv/` directory. To restore one:

```sh
python3 scripts/configure_tv.py --serial TV_IP:DEBUG_PORT --restore .kopp-tv/BACKUP.json --apply
```

Use the backup filename printed during setup. Restore checks the device and Android user, and stops if accessibility services or the Home role have changed independently. A failed operation attempts to restore its changed settings and reports any incomplete rollback.

The ten-minute timeout follows normal Android idle behavior. Video apps can keep the screen awake during playback. The separate `attentive_timeout` policy is reported and left unchanged because it can force sleep despite a video app's wake lock. See [Android's screen-on guidance](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on) and [AOSP's timeout definitions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/provider/Settings.java).

Android's idle screensaver is separate from Kopp TV's rotating background photos. The script does not change the photo feature, install or remove apps, or change the system Home role. Physical remote behavior and idle timing still need checking on your TV; the script's automated tests use a fake ADB device.

## Configure Home

On first launch, select your installed TV apps and choose **Done**. Open **Up → Manage apps** (**Halda rakendusi**) to change them later. **Arrange** lets you move selected apps earlier or later; **Done** saves the selection and order. **Cancel** or Back discards the draft. Apps that are uninstalled disappear from Home but keep their saved position for a later reinstall.

Open **Up → Settings** (**Seaded**) for:

| Setting | What it does |
| --- | --- |
| Language | Follow the system, or choose English or Eesti |
| Weather location | Search for a city and select a matching location |
| Show clock and date | Show or hide the clock and date together |
| Show weather | Show or hide the temperature line |
| Android optimisation | Scan system settings and diagnostics, apply supported changes, or restore saved settings |

The clock uses the TV's timezone and 12/24-hour setting. Dates follow the chosen locale. A new installation has no preset weather city. Weather uses [Open-Meteo](https://open-meteo.com/) without a location permission or API key. City searches send the search text to Open-Meteo; temperature requests use the selected coordinates. Hidden weather does not poll.

## How Home works

You can also enable **Kopp TV Home button** (**Kopp TV avakuva nupp**) manually in Android Settings → Accessibility. Keep Google TV as the system's default Home app.

[HomeService.java](app/src/main/java/ee/kalle/minimaltv/HomeService.java) handles the remote's Home key and recognizes ordinary launches of the Google TV Home activity. It requests key filtering and window-state events without permission to retrieve screen contents. Google TV stays installed and retains its Home role.

Choose **Up → Google TV Home** (**Google TV avakuva**) for an intentional visit to the original home screen. Returning to Kopp TV or pressing the physical Home key ends that visit. Disable the accessibility service to restore normal Google TV Home behavior.

The fallback targets Google's `com.google.android.apps.tv.launcherx`. Other manufacturers' home apps may need a source change. The app does not require root or firmware changes. Uninstalling it removes its settings and device wallpaper folder.

## Photos

The two supplied photos in [wallpapers/](wallpapers/) are separate from the APK. After opening the app once, upload them from the repository root:

```sh
for name in IMG_5681.jpeg IMG_7804.jpeg
do
  if adb push "wallpapers/$name" "/sdcard/Android/data/ee.kalle.minimaltv/files/wallpapers/.$name.tmp"
  then
    adb shell mv "/sdcard/Android/data/ee.kalle.minimaltv/files/wallpapers/.$name.tmp" "/sdcard/Android/data/ee.kalle.minimaltv/files/wallpapers/$name"
  fi
done
```

Add future photos to the same device folder using a temporary filename, then rename them after the transfer succeeds. Supported extensions are `.jpg`, `.jpeg`, `.png` and `.webp`, sorted by filename. Hidden temporary files are ignored.

Photos rotate every ten minutes while Home is visible. **Up → Next background** (**Järgmine taustapilt**) advances manually. The folder is rescanned when returning to Home and between rotations. Images are downsampled, oriented and center-cropped at runtime without rewriting the files.

The repository copies have unnecessary personal metadata removed, with compressed image data, orientation, ICC profiles and HDR auxiliary images preserved.

## Development

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
python3 -m unittest discover -s scripts/tests -v
```

Local Android tests cover app selection and order, layouts, language, Home behavior, weather and optimisation. The Python tests use stateful fake ADB endpoints to cover previews, device selection, service and permission preservation, backups, restore, command errors and rollback. Neither test suite connects to a TV.

Checked on a Chromecast HD running Android TV 14: upgrade from the original launcher, app-order and locale migration, existing photos, live weather, 24-hour time and setup-script settings. Physical Home-button and full ten-minute idle/playback checks remain manual.

The version 1.6 optimiser and permission-enabling script were also checked on that Chromecast. The live scan found three remaining changes: location access, the default assistant and voice interaction. Applying them succeeded, restoring reproduced all fourteen inspected original values exactly, and applying again left the three changes enabled. The existing animation, screensaver and ten-minute timeout settings were preserved. Bluetooth scanning was absent and was skipped.

## License

Copyright © 2026 VROTEK OÜ. The original project code, documentation and supplied photos are covered by the [MIT license](LICENSE).

Streaming-app banners and icons come from installed applications and are not bundled or relicensed. The Gradle wrapper keeps its upstream license. Weather data uses CC BY 4.0; Open-Meteo's free API is for non-commercial use. See [third-party notices](THIRD_PARTY_NOTICES.md) and [Open-Meteo's terms](https://open-meteo.com/en/terms).

---

<p align="center">
  <sub>Built for the part where you actually pick something to watch.</sub>
</p>
