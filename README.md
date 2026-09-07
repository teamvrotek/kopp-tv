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

The 47 local Android tests use Robolectric to cover app selection and order, layouts, language, Home behavior and weather. The 14 Python tests use a stateful fake ADB endpoint to cover previews, device selection, service preservation, backups, restore, command errors and rollback. Neither test suite connects to a TV.

Version 1.5 has not yet been installed or checked on a physical TV.

## License

Copyright © 2026 VROTEK OÜ. The original project code, documentation and supplied photos are covered by the [MIT license](LICENSE).

Streaming-app banners and icons come from installed applications and are not bundled or relicensed. The Gradle wrapper keeps its upstream license. Weather data uses CC BY 4.0; Open-Meteo's free API is for non-commercial use. See [third-party notices](THIRD_PARTY_NOTICES.md) and [Open-Meteo's terms](https://open-meteo.com/en/terms).

---

<p align="center">
  <sub>Built for the part where you actually pick something to watch.</sub>
</p>
