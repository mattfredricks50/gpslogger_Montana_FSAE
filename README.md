Montana FSAE Logger
=========

An Android phone as the car's datalogger. It records GPS, accelerometer, gyroscope and Speeduino
ECU data on one shared clock, splits the recording into fixed-length chunks, and uploads each
finished chunk to Google Drive. The files open directly in
[PlotJuggler](https://github.com/facontidavide/PlotJuggler).

A fork of [mendhak/gpslogger](https://github.com/mendhak/gpslogger). All FSAE work is on the
`fsae-imu` branch; `master` tracks upstream.

| | |
|---|---|
| IMU | accelerometer + gyroscope, 50–400 Hz (phones typically deliver 200–250 Hz) |
| GPS | every fix the phone's chip gives, usually 1 Hz |
| ECU | RPM, MAP, TPS, AFR, CLT, IAT and more from a Speeduino through an AirBear, up to 30 Hz |
| On the phone | live G-G diagram with peak g, strip charts, event markers, upload status |
| Off the phone | 7-minute chunks zipped to Google Drive, then converted for PlotJuggler |

## Using it

1. Install the APK. See [Building](#building), or copy `gpslogger-debug.apk` to the phone and open it.
2. Set these phone settings (Samsung phones kill background apps aggressively):
   - **Battery:** Settings → Apps → the logger → Battery → **Unrestricted**
   - **Location:** **Allow all the time**, with precise location on
   - **Notifications:** allowed. The logging notification carries the Stop, Mark and Upload buttons.
3. Sign in under **Settings → Upload → Google Drive** and turn on auto-send.
4. Mount the phone rigidly in the car and tell the app which way it faces:
   **Settings → Logging details → Phone mount: which way faces the car's nose** (default: the top
   of the phone).
5. Press **Start**, fill in the session form, and **keep the car still for the first 2 seconds**.
   That window measures gravity and gyro bias, which is what makes car-frame g possible.

On a fresh install the FSAE settings are already applied: streams and ECU on, 200 Hz, 7-minute
chunks, GPS every fix, zip upload to Drive. Everything below is adjustable under
**Settings → Logging details → FSAE datalogging**.

### While driving

- **MARK** stamps the current moment into the log, so "that noise on the back straight" is findable
  later. Press it on the G-G screen, use either **volume key** while the app is on screen, or tap
  **Mark** in the notification. Long-press MARK to type a label.
- The **upload bar** under the toolbar shows uploading progress, how many chunks are waiting for
  internet, or the last error (tap to retry). When everything is uploaded it shows how much space
  the logs take on the phone; tap it to delete the uploaded ones.
- **Session info** (name, date, tune, driver, goal, comments) can be edited any time from the
  ⋮ menu. It goes into every chunk's `.meta`, and the name goes into the file names.

### Screens

Pick these from the dropdown at the top of the main screen:

- **FSAE Status** — what's logging, rates, row counts, upload state
- **FSAE Live Charts** — accel, gyro and GPS speed strip charts (tap to change the time window)
- **FSAE G-G** — live G-G diagram with a trail, plus peak braking / acceleration / left / right /
  combined g for the session. Peaks are tracked by the logger even when no screen is open.

## Output files

Each chunk shares a base name: `<timestamp>_<session name>`, e.g. `20260922143012_Skidpad-run3`.
The timestamp comes first so chunks stay in time order; the name is optional.

| File | Contents | Rate |
|---|---|---|
| `<base>.gps` | GPS fixes | native, usually 1 Hz |
| `<base>.acc` | accelerometer, including gravity, phone axes | IMU rate |
| `<base>.gyr` | gyroscope, phone axes | IMU rate |
| `<base>.ecu` | Speeduino data via AirBear | up to 30 Hz |
| `<base>.mrk` | event markers | on demand |
| `<base>.meta` | JSON: session info, device, sensors, actual rates, calibration, marker labels, row and drop counts | once per chunk |

With zip upload on, Drive gets `<base>.zip` holding all of the above. The streams upload as
`text/csv` so Drive doesn't convert them to Sheets.

Headers:

```
.gps  time_s,utc_ms,lat_deg,lon_deg,alt_m,speed_mps,bearing_deg,hacc_m,vacc_m,speed_acc_mps,sats
.acc  time_s,accel_x_mps2,accel_y_mps2,accel_z_mps2
.gyr  time_s,gyro_x_rads,gyro_y_rads,gyro_z_rads
.ecu  time_s,airbear_ms,rpm,map_kpa,tps_pct,afr,afr_target,clt_c,iat_c,batt_v,advance_deg,
      pw1_ms,ve_pct,dwell_ms,baro_kpa,rpm_dot,tps_dot_pctps,ego_corr_pct,wue_corr_pct,
      ae_corr_pct,gamma_corr_pct,running,dfco
.mrk  time_s,marker
```

Format rules, which every stream follows:

- **`time_s` is the first column:** `(elapsedRealtimeNanos - session_start_ns) / 1e9`, 6 decimals.
  Sensors use `SensorEvent.timestamp`, GPS uses `Location.getElapsedRealtimeNanos()`, ECU rows use
  the phone's receive time. Wall-clock time appears only in `utc_ms` and `.meta`.
- **`session_start_ns` is set when you press Start.** It doesn't reset between chunks, so chunk
  files join end to end. It resets on a new Start or a phone reboot.
- **Time strictly increases within a file.** Backwards samples are dropped and counted in `.meta`.
- **One header row, units in the column names.** Plain CSV with a `.` decimal on every locale.
  Missing values are empty fields. UTF-8, no BOM, `\n` line endings.
- **Gaps are left as gaps.** Nothing is interpolated or resampled on the phone.
- **Data is raw.** Rotation into car axes and bias removal happen in post-processing from the
  `.meta` calibration. Check `accel_std_mps2` and `gyro_std_rads` to confirm the car really was
  still at Start.

GPSLogger's own `.csv` / `.gpx` / `.kml` outputs still work but aren't part of the FSAE format.

## Viewing in PlotJuggler

1. Install from the [PlotJuggler releases](https://github.com/facontidavide/PlotJuggler/releases).
2. Download the session's files from Drive (loose files, chunk `.zip`s or Drive's own download
   `.zip` all work) and run:
   ```
   python tools/plotjuggler/fsae_to_plotjuggler.py <downloaded files or folder> -o plotjuggler
   ```
   PlotJuggler only opens `.csv`, so this step is required. It joins the chunks of each session
   into `<session>_gps.csv` / `_acc.csv` / `_gyr.csv` / `_ecu.csv` / `_mrk.csv`, plus
   **`<session>_car.csv`** with car-frame g. It also prints the session info and a quality check
   from `.meta`: actual IMU rate, dropped samples, whether the car was still during calibration,
   marker labels and ECU connection errors.
3. **File → Load Data**, pick a CSV, choose `time_s` as the time column, and repeat for the other
   streams of the same session (keep the previously loaded data when asked). They line up
   automatically because they share a time base.

### Car-frame g and the G-G diagram

`<session>_car.csv` holds `long_g`, `lat_g`, `vert_g` and `yaw_rate_dps`, gravity removed and
smoothed:

| Column | Positive means |
|---|---|
| `long_g` | accelerating (negative = braking) |
| `lat_g` | accelerating toward the car's right, i.e. a right turn |
| `vert_g` | upward, 0 at rest |
| `yaw_rate_dps` | turning left (counter-clockwise from above) |

"Down" comes from the Start calibration. "Forward" is fitted from GPS when the session has enough
speeding up and slowing down; otherwise the mount setting is used. Override it with
`--forward +y|-y|+x|-x|+z|-z`, and change the smoothing with `--smooth 0.2` (seconds).

For a **G-G diagram**, load `<session>_car.csv`, select `lat_g` and `long_g`, and drag them onto a
plot with the **right** mouse button. For a **track map**, do the same with `lon_deg` and `lat_deg`
from the `.gps` file.

The maths lives in `tools/plotjuggler/car_frame.py`, with synthetic checks in
`test_car_frame.py` (`python tools/plotjuggler/test_car_frame.py`). The phone uses the same
conventions in `CarFrame.java`.

## Speeduino over AirBear

The phone reads the **Web Dash** mode of an [AirBear](https://wiki.speeduino.com/en/boards/Airbear):
AirBear polls the ECU's secondary serial `'A'` packet at 30 Hz and pushes it as JSON over
Server-Sent Events, to up to 3 clients at once, so the web dash keeps working alongside the logger.

- Speeduino's **secondary serial protocol must be "Generic (Fixed List)"**, which is what AirBear
  decodes.
- Set the address under **Settings → Logging details → AirBear address(es)**. The default tries
  `speeduino.local` then `192.168.4.1` (AirBear's own access point).
- Two ways to connect: join AirBear's Wi-Fi from the phone, or have AirBear join the phone's
  hotspot. On AirBear's Wi-Fi there's no internet, so the app keeps that link for ECU data while
  Drive uploads go over mobile data.
- Scaling is corrected against Speeduino's firmware: TPS is 0–200 in 0.5% steps, `advance` is
  int8, `rpmDOT` int16, `tpsDOT` `(int8)*10`, and PW is microseconds. AirBear's JSON reports
  several of these raw, so its own dash reads TPS at double.
- Repeat readings are skipped (AirBear resends its last reading every tick), and the app
  reconnects by itself. Counters and the last error are in `.meta`.

For bench testing without the car, `python tools/fake_airbear.py 8080` serves the same stream from
a PC; point the app's AirBear address at `<pc-ip>:8080`.

## Building

Requires the Android SDK and **JDK 17 or 21**. Gradle 8.9 doesn't run on Java 8 or on Android
Studio's bundled Java 25.

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :gpslogger:assembleDebug
adb install -r gpslogger\build\outputs\apk\debug\gpslogger-debug.apk
```

Unit tests:

```powershell
.\gradlew.bat :gpslogger:testDebugUnitTest --tests "com.mendhak.gpslogger.loggers.fsae.*"
```

`DialogsTest.GetFormattedErrorMessageForPlainText_WithMessageAndThrowable` fails on Windows
because it expects `\r\n`; it fails on unmodified upstream too.

### Where the code is

```
loggers/fsae/         FsaeLogger      writes .gps/.acc/.gyr/.mrk/.meta, calibration, chunk rotation
                      EcuLogger       AirBear SSE client -> .ecu          SseParser
                      CsvStreamWriter buffered CSV, locale-free number formatting
                      CarFrame        phone axes -> car axes              GgTracker  peak g
                      LiveTelemetry   ring buffers feeding the live screens
common/               FsaeDefaults    one-time FSAE settings              SessionInfo
senders/googledrive/  DriveUploadStatus  upload progress and history      UploadedLogCleaner
ui/components/        GgPlotView  StripChartView  UploadStatusBar
ui/fragments/display/ FsaeGgFragment  FsaeChartsFragment  FsaeStatusFragment
tools/plotjuggler/    fsae_to_plotjuggler.py  car_frame.py  test_car_frame.py
tools/                fake_airbear.py
```

The FSAE logger is driven from `GpsLoggingService`, which also handles chunk rotation, the
wake lock and the notification actions.

## Google Drive login

The app is `com.fsae.logger` (debug builds: `com.fsae.logger.debug`). It uses the team's own Google
Cloud OAuth client, set in `GoogleDriveManager.getGoogleDriveApplicationClientID()`.

Google matches sign-in by **package name + signing key SHA-1**. If you build with a different debug
keystore (a different PC), or make a release build, add another **Android** OAuth client in the same
Google Cloud project:

- **Package name:** `com.fsae.logger.debug` for debug, `com.fsae.logger` for release
- **SHA-1:** from `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`

Project settings in Google Cloud:
- Google Drive API enabled
- Scope `https://www.googleapis.com/auth/drive` declared (full Drive, so the app can write into the
  team Shared Drive). This is a restricted scope: sign-in shows an "unverified app" warning
  (Advanced → continue), and unverified apps are capped at 100 users.
- Audience **External** and **In production**. In Testing mode, refresh tokens expire after 7 days.
- On the Android client, **Advanced settings → Enable custom URI scheme** must be ticked. The
  redirect is `com.fsae.logger.debug:/oauth2googledrive`; without this, sign-in fails with
  `400 invalid_request`.

### Uploading to the team Shared Drive

In the app's Google Drive settings, set **Google Drive folder path** to the Shared Drive folder's
link (`https://drive.google.com/drive/folders/…`) or to `id:FOLDER_ID`. A plain name or path
(`aaa/bbb`) keeps upstream behaviour: the app creates folders in your own My Drive.

The signed-in account must be a member of the Shared Drive with at least **Contributor** access.
Upload errors include Drive's response (404 = the account can't see the folder, 403 = no permission
to add files). After changing scopes, use **Clear authorization** and sign in again.

Downloaded `client_secret_*.json` files are git-ignored. Android OAuth clients have no secret, but
keep them out of the repo anyway.

## Known limits

- **GPS is 1 Hz** on the phones tested, even though the app now asks the chip for 10 Hz. Lap timing
  to better than about half a second needs an external receiver (a 10–25 Hz Bluetooth unit, or a
  u-blox module on the car's CAN bus).
- **ECU data is capped at 30 Hz** by AirBear's polling, and carries no ECU-side timestamp, so
  `time_s` is the phone's receive time.
- **The phone must not move in its mount after Start**, or the car-frame g will be wrong.
- **Phones overheat in direct sun** and will eventually throttle or stop logging.

## Roadmap

- Auto-start logging (on car power or on joining AirBear's Wi-Fi) and a storage guard that clears
  uploaded chunks automatically
- Alarms while running: coolant, lean AFR under load, low battery, ECU dropout
- A tuning report from the logs: VE table coverage and AFR error per RPM/MAP cell
- Lap timing, and IMU + GPS fusion for smooth speed between fixes
- Longer term: an ESP32 CAN-to-BLE bridge, so the logger also works with a Haltech ECU

## License

GPL-2.0, inherited from upstream ([LICENSE.md](LICENSE.md)). If the app is distributed outside the
team, the modified source must be made available.

## Upstream

This fork keeps upstream GPSLogger's own features (GPX/KML/NMEA logging, SFTP, Dropbox, OpenStreetMap,
OwnCloud, email, custom URL). For those, and for the upstream development notes, see
[the GPSLogger repository](https://github.com/mendhak/gpslogger) and
[gpslogger.app](https://gpslogger.app/). To pull upstream changes into this fork:

```
git fetch upstream && git merge upstream/master
```
