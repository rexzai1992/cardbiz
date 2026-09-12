# CardBiz ECR Test APK - Product Structure

## Purpose

This is a diagnostic Android application for proving communication between an Android phone/tablet and a CardBiz/Castles payment terminal. It is not a production POS application and must only be used with a CardBiz-approved test terminal and test merchant profile.

## Proof sequence

1. Select a transport and connect.
2. Send Ping (`FF`) and confirm ACK, a valid response frame, response ACK and an `FF/00` or `FF/CP` response (terminal firmware differs).
3. Only after Ping succeeds, confirm test mode in the app and exercise Purchase (`20`) with a test amount via card (`M1=01`), e-wallet QR shown on the terminal (`M1=99`) or terminal-camera scan of the customer's wallet QR (`M1=02`). Pay Account ID remains optional for Purchase.
4. Use Last Transaction Inquiry (`12`) to inspect the latest test transaction.
5. Use Get Batch Detail (`51`) only on a test profile. Settlement (`50`) has a separate safety gate because it changes terminal batch state.

## Modules

- `protocol`: CardBiz BCD lengths, STX/ETX/LRC frames, presentation/transport headers, field encoding and response parsing.
- `session`: ACK/NACK state machine, two resends (three total sends), two-minute response timeout and multi-message responses.
- `transport`: Bluetooth RFCOMM, USB host serial, experimental TCP socket and vendor SDK adapter seam.
- `ui`: one POS-style test screen with prominent Ping and two Pay actions (card, e-wallet QR) plus a secondary scan-customer-QR option; advanced connection settings, parsed response fields, raw hex and Logcat mirroring remain available below.

## Supported transport status

- Bluetooth: raw RFCOMM. The PDF supplies the protocol name `com.castles.protocolCastles` but no RFCOMM service UUID. The app exposes a UUID field and defaults to the standard Serial Port Profile UUID for an initial test.
- USB serial/OTG: automatically detects bulk serial devices on launch/attach, disconnects on unplug and reconnects on replug. It uses raw USB host endpoints with CDC ACM line coding (9600, 8N1). The first attach can require Android approval; some chipsets need a vendor-specific driver or CardBiz SDK.
- WiFi TCP/IP: client mode (app connects to terminal IP:port) and listen mode (terminal connects to the app). Version 1.59 does not define a host, port, role or TCP framing; CardBiz must confirm those settings before this mode can be considered supported.
- Vendor SDK: `VendorSdkTransport` is a documented placeholder for a supplied Android AAR.

## Safety boundaries

- No merchant secret, production credential or fixed terminal identifier is stored.
- Purchase and settlement require an explicit on-screen test-mode acknowledgement.
- Purchase routing is limited to the three explicit Merchant Index buttons (`01`, `02`, `99`); manual merchant selection (`M1=00`), EPP and refund/void commands are not exposed.
- Settlement also requires a second, separate acknowledgement.
- Logs are kept in memory, mirrored to Logcat and removable with Clear logs.
- The app does not capture PIN, track data, PAN or cardholder input.

## Deliverables

- Native Kotlin Android Studio project.
- Debug APK under `app/build/outputs/apk/debug/` after a successful build.
- Unit tests for protocol framing/parsing.
- `README.md` for operator setup and CardBiz dependencies.
- `PROTOCOL_NOTES.md` mapping implementation decisions to specification v1.59.
