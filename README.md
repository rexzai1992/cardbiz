# CardBiz ECR Test APK

A small native Kotlin Android application with a POS-style test screen for proving Android-to-terminal CardBiz ECR communication. It implements the byte-level protocol from **CardBiz ECR Integration Functional Specs v1.59 (2026-06-15)** and intentionally has no production checkout, receipt or credential storage.

## Safety

Use only a CardBiz/Castles terminal configured with a test merchant profile.

- Ping (`FF`) is the first connection test when no Pay Account ID is available.
- Purchase stays locked until Ping returns a valid `FF/00` or `FF/CP` response and the operator confirms test mode. Both responses have been observed across terminal firmware.
- Test Purchase offers three routes via Merchant Index: `M1=01` card, `M1=99` e-wallet QR (terminal displays a DuitNow QR for the customer's e-wallet to scan) and `M1=02` scan-customer-QR (terminal camera reads the customer's wallet barcode). All three share the same Ping and test-mode gate.
- A Purchase is successful only for a validated terminal response with response indicator `1`, transaction code `20`, and response code `00`. The main screen then shows `PAYMENT SUCCESS` with the amount and available receipt references; raw data remains in diagnostics.
- Settlement changes terminal batch state. It requires test-mode confirmation plus a separate Settlement confirmation.
- Do not enter production merchant secrets. The app does not ship with any Pay Account ID.
- Raw protocol data is shown on screen and mirrored to Logcat for diagnostics. Do not collect logs from production transactions.

## What is implemented

- Bluetooth paired-device list and discovery.
- Bluetooth Classic RFCOMM connection with an editable service UUID.
- Automatic USB/OTG detection, Android USB permission prompt, reconnect-on-plug, CDC ACM 9600/8N1 configuration and bulk I/O.
- Experimental direct TCP socket mode for terminals whose CardBiz configuration explicitly supports it.
- Simple POS-style Ping, Pay by Card, Pay by E-wallet QR and Last Sale buttons, plus a secondary scan-customer-QR option, with connection settings and raw logs kept under Connection & Diagnostics.
- Connect/disconnect state, raw TX/RX hex, ACK/NACK events and parsed response fields.
- Ping (`FF`), Verify ID (`00`), Purchase (`20` with `M1=01`, `M1=02` or `M1=99`), Last Transaction Inquiry (`12`), Settlement (`50`), Get Batch Detail (`51`) and Cancel (`XX`).
- Card purchases record the card type (`D4`, falling back to issuer `D2`), the last four digits of the masked card number (`30`), entry mode (`E6`), RRN, batch, terminal ID, merchant number and terminal date/time in the payment result. The CVV is never returned by the terminal and is not collected.
- Kiosk mode: the app never prints, and by default appends Receipt Required `09` = `0` to every Purchase. v1.59 removed this field from Purchase, so terminals may ignore it; if the terminal still prints, ask CardBiz to disable receipt printing in the terminal's ECR profile. The toggle is under Connection & Diagnostics.
- Wallet custom data in field `99` (tag/length/value, e.g. `B910` wallet product name) and DuitNow tags `N8`/`N4`/`N5`/`N6` are parsed and shown in the payment result.
- Packed-BCD lengths, STX/ETX/LRC, 2-second ACK timeout, three total sends, 2-minute response timeout, response ACK/NACK and multi-message responses.
- Unit tests matching the PDF's Verify ID, card Purchase, wallet Purchase and DuitNow QR Purchase sample frames byte-for-byte, plus wallet and DuitNow response decoding.

## Build

Requirements:

- Android Studio with JDK 17, or JDK 17 plus the Android command-line SDK.
- Android SDK Platform 35 and Build Tools 35.0.0.

Open this directory in Android Studio and select **Build > Build APK(s)**, or run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A versioned copy from the verified build is also placed at:

```text
output/apk/CardBiz-ECR-Test-0.4.0-debug.apk
```

Install on a connected test device with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First terminal test

1. Confirm with CardBiz that the terminal is on a test merchant profile and ECR mode is enabled.
2. Pair Android and the terminal in Android Settings if using Bluetooth.
3. For USB/OTG, plug in the terminal and approve Android's USB prompt once. The app connects automatically, disconnects on unplug, and reconnects when plugged back in.
4. For Bluetooth, open **Connection & Diagnostics**, grant Nearby Devices permission, select the paired terminal and tap **Connect**.
5. Bluetooth defaults to SPP UUID `00001101-0000-1000-8000-00805F9B34FB`; replace it if CardBiz supplies a different RFCOMM UUID.
6. Tap **Connect Test / Ping** and confirm `RX ACK`, valid LRC and an `FF/00` or `FF/CP` response.
7. Tick the test-profile confirmation and tap **Pay by Card** with a low value such as `0.01`. The app sends `M1=01`, so the terminal goes directly to its tap/insert-card screen.
8. To test e-wallet QR, tap **Pay by E-wallet QR**. The app sends `M1=99`; the terminal displays a DuitNow QR that the customer scans with TNG, Boost, GrabPay or a banking app. A successful response carries the `N5` bill number and `N6` PayNet Biz Msg ID.
9. The smaller alternative button sends `M1=02`; the terminal opens its camera and reads the QR / barcode shown in the customer's wallet app. A successful response carries card entry mode `60` and wallet custom data in field `99` (for example `B910` = `TNG WALLET`).

If the terminal answers a wallet or DuitNow request with response code `TE` or a merchant-selection error, that merchant index is not configured on the terminal profile. Ask CardBiz to enable the wallet / DuitNow merchant on the test terminal; the app-side framing matches the v1.59 samples byte-for-byte.

Pay Account ID is optional for Purchase. Leave it blank when CardBiz has only supplied MID/TID; never substitute MID or TID into that field. Commands such as Verify ID, Last Transaction Inquiry, Batch Detail and Settlement still require the 20-character Pay Account ID.

The amount is converted to a 12-digit implied-decimal field: `0.01` becomes `000000000001` and `1.00` becomes `000000000100`.

## Bluetooth limitation in the PDF

Appendix E gives the protocol/service name:

```text
com.castles.protocolCastles
```

That is not an Android RFCOMM UUID. Android needs the terminal's SDP UUID/channel or a vendor SDK that resolves the service. The app therefore makes the UUID editable and starts with the common Serial Port Profile UUID. A failure with the default UUID does not prove the ECR protocol is incompatible; it means the Bluetooth transport parameters still need confirmation.

## USB limitation

Appendix F defines RS232 as 9600 bps, no parity, 8 data bits, 1 stop bit, retries 3. This app applies 9600/8N1 to CDC ACM USB devices and uses bulk endpoints. CH340, CP210x, FTDI, Prolific or proprietary Castles interfaces can require a chipset driver or the vendor AAR. Use a data-capable OTG cable and confirm whether the terminal is DCE/DTE and whether a null-modem adapter is required for a USB-to-RS232 bridge.

## TCP/IP limitation

The v1.59 PDF does not define TCP/IP, a port, the terminal's client/server role, or TLS. TCP mode is included only as an experimental direct byte stream. Do not assume it is supported until CardBiz provides the endpoint and confirms that the same ECR frame is carried without an extra transport envelope.

## What CardBiz/Castles must provide

Ask for:

1. Android integration package: AAR/JAR, Maven coordinates if any, API documentation, sample application and supported Android/API/ABI versions.
2. License/activation requirements and a test merchant profile/Pay Account ID. Keep supplied credentials out of Git.
3. Bluetooth pairing workflow, SDP service UUID or RFCOMM channel, and confirmation of how `com.castles.protocolCastles` maps to Android.
4. Supported terminal models and firmware versions, plus the menu/configuration needed to enable external-device ECR.
5. USB VID/PID list, interface type and driver requirements; exact cable/bridge wiring.
6. TCP server/client role, port, IP setup, framing, TLS and keepalive rules if TCP ECR is supported.
7. Expected behavior for duplicate transaction IDs, timeout/reversal recovery, settlement in test mode and certification test cases.

## Adding a vendor AAR

Place the supplied file at `app/libs/cardbiz-ecr-sdk.aar`, then add this dependency to `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/cardbiz-ecr-sdk.aar"))
    testImplementation("junit:junit:4.13.2")
}
```

Implement the methods in `app/src/main/java/com/cardbiz/ecrtest/transport/VendorSdkTransport.kt`, mapping the SDK's connection and byte/callback APIs to `EcrTransport`. If the SDK builds ECR messages itself, retain the raw codec unit tests as an independent reference and document which layer owns ACK/NACK handling.

## Source layout

```text
app/src/main/java/com/cardbiz/ecrtest/
  MainActivity.kt                 test UI and Android permissions
  protocol/                       frame/command codec
  session/                        ACK/NACK transaction state machine
  transport/                      Bluetooth, USB, TCP and SDK seam
app/src/test/                     protocol sample-vector tests
PRODUCT_STRUCTURE.md             scope and safety boundaries
PROTOCOL_NOTES.md                implementation mapping to v1.59
```

## Known scope boundaries

- Test harness only; not certified as a POS integration.
- No automatic reversal or duplicate-suppression ledger.
- No receipt rendering, PAN storage, PIN capture or production key management.
- Raw USB support is intentionally generic and may need a driver for the actual bridge chipset.
- No assumptions are made about TCP support beyond a simple socket.
