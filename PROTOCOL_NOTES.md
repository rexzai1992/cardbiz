# Protocol Notes - CardBiz ECR v1.59

Source reviewed: `CardBiz ECR Integration Functional Specs 1.59_20260615 - Payment Terminal.pdf`.

## Frame

```text
STX (02)
Length (2 packed-BCD bytes; decimal length of Message Data)
Message Data
ETX (03)
LRC (XOR of Length, Message Data and ETX; STX excluded)
```

Message Data contains:

```text
Transport header:    "60" + "0000" + "0000"
Presentation header: version + request/response + transaction code
                     + response code + more-to-follow + 1C
Fields:              2 ASCII field-code bytes + 2 packed-BCD length bytes
                     + ASCII data + 1C
```

The document says the LRC is the XOR of data between STX and ETX, including ETX. Its sample Verify ID LRC (`57`) proves the two outer BCD length bytes are also included; the app follows the sample.

## Control flow

- Send command and wait up to 2 seconds for ACK (`06`).
- On NACK (`15`), resend immediately.
- On no ACK, resend after the 2-second wait.
- At most two resends are made (three sends total).
- After ACK, wait up to the recommended 2 minutes for the response.
- Validate response length, ETX and LRC.
- Send ACK for a valid response or NACK for a corrupt response.
- Acknowledge and collect every response whose presentation header says more-to-follow is `1`.

## Implemented commands

| Name | Code | Request fields |
|---|---:|---|
| Verify Pay Account ID | `00` | `00` Pay Account ID (20) |
| Last Transaction Inquiry | `12` | `00` Pay Account ID (20), `66` Transaction ID (20) |
| Purchase | `20` | optional `00`, `66`, `40`, `M1=01` in this test APK |
| Settlement | `50` | `00`, `66`, `52` force flag (`0` in this app) |
| Get Batch Detail | `51` | `00`, `66` |
| Cancel Transaction | `XX` | `00`, `66` |

The card-only test APK intentionally does not expose code `15` (QR Inquiry), wallet purchases, or DuitNow purchases.

## Transport gaps requiring CardBiz confirmation

The Bluetooth appendix provides only `com.castles.protocolCastles`. Android RFCOMM requires an SDP service UUID/channel; a protocol/service name alone cannot identify it. Request the Android AAR or exact RFCOMM UUID and pairing instructions.

The PDF contains no TCP/IP endpoint or TCP transport definition. Request the terminal ECR TCP server/client role, IP/port configuration, framing differences, keepalive expectations and TLS requirements.

The RS232 appendix specifies 9600 baud, no parity, 8 data bits, 1 stop bit and retries 3. For Android USB OTG, request the supported USB VID/PID list, serial chipset/driver, cable/adapter requirements and whether the terminal exposes CDC ACM or a proprietary USB interface.
