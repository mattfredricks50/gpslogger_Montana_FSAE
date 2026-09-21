Montana FSAE Logger
=========

A fork of [mendhak/gpslogger](https://github.com/mendhak/gpslogger) that turns an Android phone into the car's datalogger. It logs GPS, accelerometer and gyroscope data on one shared clock, splits logs into fixed-length chunks, and uploads each chunk to Google Drive. The files are laid out to open directly in [PlotJuggler](https://github.com/facontidavide/PlotJuggler).

All FSAE work is on the `fsae-imu` branch. `master` tracks upstream.

## Using it

1. Install the APK. See [Building](#building) below, or copy `gpslogger-debug.apk` to the phone and open it.
2. Set these phone settings (Samsung phones kill background apps aggressively):
   - **Battery:** Settings → Apps → the logger → Battery → **Unrestricted**
   - **Location:** **Allow all the time**, with precise location on
   - **Notifications:** allowed. The logging notification has the stop button.
3. In the app's logging settings, under **FSAE datalogging**:
   - **Log GPS + IMU streams:** on
   - **IMU sample rate:** 50 / 100 / 200 / 400 Hz (default 200). Android 12+ caps apps at 200 Hz unless they have the high-sampling-rate permission.
   - **New file every:** 7 minutes (0 = off). Each finished chunk is auto-sent when it closes.
4. Set up Google Drive under the upload settings, sign in, and turn on auto-send.
5. Mount the phone rigidly in the car, then press **Start** and **keep the car still for the first 2 seconds**. The logger uses that window to measure gravity and gyro bias.

## Output files

Each chunk shares a base name, e.g. `20260921143012`:

| File | Contents | Rate |
|---|---|---|
| `<base>.gps` | GPS fixes | native, ~1 Hz (some phones 10 Hz) |
| `<base>.acc` | accelerometer, including gravity, phone axes | IMU rate |
| `<base>.gyr` | gyroscope, phone axes | IMU rate |
| `<base>.meta` | JSON: device, sensors, actual rates, calibration, row/drop counts | once per chunk |

With zip upload enabled, Drive gets `<base>.zip` containing the files above. The streams are uploaded as `text/csv`, so Drive does not convert them to Sheets.

Headers:

```
.gps  time_s,utc_ms,lat_deg,lon_deg,alt_m,speed_mps,bearing_deg,hacc_m,vacc_m,speed_acc_mps,sats
.acc  time_s,accel_x_mps2,accel_y_mps2,accel_z_mps2
.gyr  time_s,gyro_x_rads,gyro_y_rads,gyro_z_rads
```

Format rules, which every stream follows (including the planned `.ecu` stream):

- **`time_s` is the first column:** `(elapsedRealtimeNanos - session_start_ns) / 1e9`, with 6 decimals. Sensors use `SensorEvent.timestamp` and GPS uses `Location.getElapsedRealtimeNanos()`. Wall-clock time is only in `utc_ms` and `.meta`.
- **`session_start_ns` is set when you press Start.** It does not reset between chunks, so chunk files join end to end. It resets only on a new Start or a phone reboot.
- **Time strictly increases within a file.** Samples that go backwards are dropped and counted in `.meta`.
- **One header row, with units in the column names.** Plain CSV with a `.` decimal on every locale. Missing values are empty fields. UTF-8, no BOM, `\n` line endings.
- **Gaps are left as gaps.** Nothing is interpolated or resampled on the phone.
- **Data is raw.** Axis rotation into car frame and bias removal happen in post-processing, using the calibration block in `.meta`. Check `accel_std_mps2` and `gyro_std_rads` to confirm the car really was still.

GPSLogger's own `.csv` / `.gpx` / `.kml` outputs still work but are not part of the FSAE format.

## Viewing in PlotJuggler

1. Install from the [PlotJuggler releases](https://github.com/facontidavide/PlotJuggler/releases).
2. **File → Load Data**, pick a `.gps`, `.acc` or `.gyr` file, and choose `time_s` as the time column. Repeat for the other files. They line up automatically because they share a time base.
3. For a quick track map, plot `lon_deg` against `lat_deg` as an XY curve. For a g-g diagram, plot two accel axes against each other as an XY curve (after rotating into car axes).

## Building

Requires the Android SDK and **JDK 17 or 21**. Gradle 8.9 does not run on Java 8 or on Android Studio's bundled Java 25.

```powershell
$env:JAVA_HOME="C:\Users\craft\.jdks\jbr-21.0.11"
.\gradlew.bat :gpslogger:assembleDebug
adb install -r gpslogger\build\outputs\apk\debug\gpslogger-debug.apk
```

Unit tests for the FSAE writers are in `gpslogger/src/test/java/com/mendhak/gpslogger/loggers/fsae/`:

```powershell
.\gradlew.bat :gpslogger:testDebugUnitTest --tests "com.mendhak.gpslogger.loggers.fsae.*"
```

The FSAE code lives in `gpslogger/src/main/java/com/mendhak/gpslogger/loggers/fsae/` (`FsaeLogger`, `CsvStreamWriter`). It is wired into `GpsLoggingService`.

## Google Drive login

The app is `com.fsae.logger` (debug builds: `com.fsae.logger.debug`). It uses the team's own Google Cloud OAuth client, set in `GoogleDriveManager.getGoogleDriveApplicationClientID()`.

Google matches the sign-in by **package name + signing key SHA-1**. If you build with a different debug keystore (a different PC), or make a release build, add another **Android** OAuth client in the same Google Cloud project:

- **Package name:** `com.fsae.logger.debug` for debug, or `com.fsae.logger` for release
- **SHA-1:** from `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`

Project settings in Google Cloud:
- Google Drive API enabled
- Scope `https://www.googleapis.com/auth/drive.file` declared
- Audience **External** and **In production**. In Testing mode, refresh tokens expire after 7 days.

Downloaded `client_secret_*.json` files are git-ignored. Android OAuth clients have no secret, but keep them out of the repo anyway.

## Roadmap

- `.ecu` stream: Speeduino output channels over AirBear (TCP, TunerStudio protocol), timestamped at receive time on the same clock
- Longer term: ESP32 CAN-to-BLE bridge, so the logger also works with a Haltech ECU

## License

GPL-2.0, inherited from upstream ([LICENSE.md](LICENSE.md)). If the app is distributed outside the team, the modified source must be made available.

---

# Upstream GPSLogger README

Everything below is the original upstream documentation.

GPSLogger  [![githubactions](https://github.com/mendhak/gpslogger/workflows/Android%20CI/badge.svg)](https://github.com/mendhak/gpslogger/actions) [![pgp](assets/pgp.png)](https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x95e7d75c76cbe9a9) [![Weblate](https://hosted.weblate.org/widgets/gpslogger/-/android/svg-badge.svg)](https://hosted.weblate.org/engage/gpslogger/)
=========

GPSLogger is an Android app that logs GPS information to various formats (GPX, KML, CSV, NMEA, Custom URL) and has options for uploading (SFTP, OpenStreetMap, Google Drive, Dropbox, Email). This app aims to be as battery efficient as possible.

[Read about GPSLogger's features here](https://gpslogger.app/)

## Download

You can find it on [F-Droid](https://f-droid.org/en/packages/com.mendhak.gpslogger/) 

You can download directly [from the releases](https://github.com/mendhak/gpslogger/releases).



## Contribute

You can help with [translations](https://hosted.weblate.org/engage/gpslogger/) on Weblate.    

You can also submit [pull requests](https://help.github.com/articles/using-pull-requests) for bug fixes and new features.

I'm not very good at UIs, so any work with the layouts would be appreciated!  


## License and policy

[Licensed under GPL v2](LICENSE.md) | [Third party licenses](assets/text/opensource.md) | [Privacy policy](assets/text/privacypolicy.md)



## Verifying

It's good practice to verify downloads. A PGP signature, Cosign bundle, and an SHA256 checksum will accompany each `.apk`.

To verify the PGP integrity and signature:

```bash
gpg --recv-key 6989CF77490369CFFDCBCD8995E7D75C76CBE9A9
gpg --verify gpslogger-132.apk.asc
```

(Experimental) To verify with [Sigstore Cosign](https://docs.sigstore.dev/cosign/system_config/installation), the command should be in the releases notes, it will look like this: 

```bash
cosign verify-blob gpslogger-132.apk \
--bundle gpslogger-132.apk.cosign.bundle --new-bundle-format \
--cert-oidc-issuer https://token.actions.githubusercontent.com \
--cert-identity https://github.com/mendhak/gpslogger/.github/workflows/generate-release-apk.yml@refs/head/master
```    


To verify the checksum:    
    
```bash
sha256sum -c gpslogger-132.apk.SHA256
```



Setting up the code
=========


The project is based on the [Android build system](http://tools.android.com/tech-docs/new-build-system/user-guide) plugin for Gradle.
These instructions are for Ubuntu Linux with Android Studio, but for other OSes, it should be roughly similar. 

### Set up your Android Development Environment

Follow the instructions on the [Android Developer Website](http://developer.android.com/sdk/installing/index.html) to set up your computer for development.

Download and install [Android Studio](https://developer.android.com/studio/install#linux) (there's also a [snap](https://snapcraft.io/android-studio))


### Clone the GPSLogger repository

    git clone git://github.com/mendhak/gpslogger.git

### Get the Android SDK extra repositories

This project uses certain Android libraries, you can install them using Google's poorly implemented [`sdkmanager`](https://developer.android.com/studio/command-line/sdkmanager.html):

      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'tools'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'platform-tools'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'build-tools;26.0.2'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'platforms;android-27'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'platforms;android-25'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'extras;google;m2repository'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'extras;android;m2repository'
      echo y | $HOME/android-sdk/tools/bin/sdkmanager 'extras;google;google_play_services'


### Create local.properties

Create a file called `local.properties`, pointing at your Android SDK directory.

    cd gpslogger
    echo "sdk.dir=/home/mendhak/Programs/Android" > local.properties

### Import the project

Open up Android Studio and choose to import a project.  Select the topmost `build.gradle` file under GPSLogger.

If you get an Import dialog, choose to *Import project from external model*

![import](assets/import_1.png)

On the next screen, choose the defaults and proceed (default gradle wrapper)

![import](assets/import_2.jpg)

Give it a minute and Android Studio will configure the projects and download the various libraries.

### OpenStreetMap Setup (Optional)

Sign up for an account with [OpenStreetMap](https://openstreetmap.org) and log in.

Click on 'My Settings', then 'OAuth2 Applications'

Click on 'Register your application'

Fill in the form with these details. Remember to uncheck the 'Confidential Application' checkbox, since this is a mobile app. 

![Oauth settings](assets/osm_oauth_settings.png)

After registering the application, you will receive a Client ID.   
Place the Client ID in [OpenStreetMapManager#getOpenStreetMapClientID\(\)](gpslogger/src/main/java/com/mendhak/gpslogger/senders/osm/OpenStreetMapManager.java).   
If you used your own custom scheme, replace the value in [AndroidManifest.xml](gpslogger/src/main/AndroidManifest.xml) and [OpenStreetMapManager#getOpenStreetMapRedirect\(\)](gpslogger/src/main/java/com/mendhak/gpslogger/senders/osm/OpenStreetMapManager.java) 


### Dropbox Setup (Optional)

Sign up for an account with Dropbox.com

Go to the [Dropbox Developers page](https://www.dropbox.com/developers/apps) and click on 'Create an App'

Use these settings, but choose a unique name

![Dropbox settings](assets/dropbox_settings_create.png)

After creating the app, you will receive an app key and secret (the ones in the screenshot are fake)

![Dropbox settings](assets/dropbox_settings.png)

Place the keys in your `~/.gradle/gradle.properties` like this:


    GPSLOGGER_DROPBOX_APPKEY=abcdefgh
    GPSLOGGER_DROPBOX_APPSECRET=1234123456


Replace the Dropbox app key to your AndroidManifest.xml file

    <!-- Change this to be db- followed by your app key -->
    <data android:scheme="db-12341234"/>

### Google Drive Setup (optional)

Sign up to [Google Cloud Platform](https://console.cloud.google.com/).  Create a new project. 

Under APIs and Services, [enable the Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com).  
Next, go to the [Oauth Consent Screen](https://console.cloud.google.com/apis/credentials/consent), going through the steps until you reach scopes. 
Add the `https://www.googleapis.com/auth/drive.file` scope.  

![scopes](assets/googledrive_scope.png)

[Create some OAuth credentials](https://console.cloud.google.com/apis/credentials), of type Android.  
Under package name, use `com.mendhak.gpslogger`. For the SHA-1 Certificate fingerprint, get it using the `keytool -keystore ~/.android/debug.keystore -list -v` command.

![oauth](assets/googledrive_oauthclient.png)


Overview
======

GPSLogger is composed of a few main components;

![design](assets/gpslogger_architecture.png)

### Event Bus

The Event Bus is where all the cross communication happens.  Various components raise their events on the Event Bus,
and other parts of the application listen for those events.  The most important one is when a location is obtained,
 it is placed on the event bus and consumed by many fragments.

### GPS Logging Service

GPSLoggingService is where all the work happens.  This service talks to the location providers (network and satellite).
It sets up timers and alarms for the next GPS point to be requested.  It passes location info to the various loggers
so that they can write files.  It also invokes the auto-uploaders so that they may send their files to DropBox, OSM, etc.

It also passes information to the Event Bus.

### GPS Main Activity

This is the main visible form in the app.   It consists of several 'fragments' - the simple view, detailed view and big view.

It takes care of the main screen, the menus and toolbars.

The fragments listen to the Event Bus for location changes and display it in their own way.

### Session and AppSettings

Floating about are two other objects.  `Session` contains various pieces of information related to the current GPSLogger run,
such as current file name, the last known location, satellite count, and any other information which isn't static but is
needed for the current run of GPSLogger.

`AppSettings` is a representation of the user's preferences.

These objects are visible throughout the application and can be accessed directly by any class, service, activity or fragment.


## Assembling the APK for Github release

The 'assemble' Gradle task will build, and it also looks for a GPG key to sign the APK with. It needs some setup first:

Create `~/.gradle/gradle.properties` which contains the release store and its key details, as well as the GPG key details

```
RELEASE_STORE_FILE=/path/to/the.keystore
RELEASE_STORE_PASSWORD=xxxxxxxxxxxxxxxxxx
RELEASE_KEY_ALIAS=gpsloggerkey
RELEASE_KEY_PASSWORD=xxxxxxxxxxxxxxxxxx
signing.gnupg.keyName=xxxxxxxxxxxxxxxxxx
signing.gnupg.passphrase=xxxxxxxxxxxxxxxxxx
```

Ensure that gpg2 is installed

```bash
sudo apt install gnupg2
```

And ensure that the above gnupg.keyname is in the gpg keystore, have a look using `gpg2 --list-secret-keys`

Once these pieces are in place, the 'assemble' task should build the APK, sign it, and create a checksum too.    
If it doesn't appear in the gpslogger folder, run 'copyFinalAPK' so that it copies the APK, ASC and SHA256 files to the gpslogger folder.  
Finally upload to Github Releases.  

## F-Droid release

F-Droid watches the Github repository for tags, and will build those tags, and sign it using its own key. So, there isn't too much to do. 

Ensure that [gpslogger/build.gradle](gpslogger/build.gradle#L47-L48) `versionCode` and `versionName` contains the latest version number to be released. 

Finally tag the commit, 

```bash
git tag -s v128
git push origin master --tags
```

## Working notes for F-Droid

Use the fdroidserver docker image.  Clone the fdroid metadata repo and make changes to the com.mendhak.gpslogger.yml file. 

    git clone https://gitlab.com/fdroid/fdroiddata.git
    cd fdroiddata

    # https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
    # initialize the metadata repo
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master init -v
    
    # lint your metadata yml
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master lint com.mendhak.gpslogger -v
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master readmeta
    
    # see if the latest tag will get picked up. 
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master checkupdates --auto com.mendhak.gpslogger
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master rewritemeta com.mendhak.gpslogger

    # build
    docker run --rm -v /home/mendhak/Android/Sdk:/opt/android-sdk -v $(pwd):/repo -e ANDROID_HOME:/opt/android-sdk registry.gitlab.com/fdroid/docker-executable-fdroidserver:master build -v -l com.mendhak.gpslogger
    
    
