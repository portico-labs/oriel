# Oriel

An Android client for [Sill](https://github.com/Saffsanity/sill): see a Mac's windows, or its whole
desktop, on an Android phone or tablet, and use them with touch, a stylus, a mouse or a keyboard.
Oriel speaks Sill's wire protocol, so it connects to the free Sill for Mac app as it is. It is an
independent project, not made by or affiliated with Sill's author.

## What works (MVP)

- **Finding Macs**: Bonjour (`_sill._tcp`) on the local network, or an address typed in (a LAN or
  Tailscale address, or a development `SillHost`).
- **Pairing**: the 12-digit code, as Sill's iPad app does it. Oriel asks the Mac to show a code,
  you type it, and both sides prove the code over TLS 1.3 with both keys pinned. The Mac's key is
  saved, and later connections pin it.
- **The picture**: HEVC decoded in hardware (`MediaCodec`, low-latency mode) straight onto a
  Surface, sized to the stream's aspect ratio.
- **Input**: tap to click, long press to right-click, one or two fingers to scroll (with the Mac's
  rubber band and a coast after a flick). A stylus or a mouse acts as the Mac's mouse, and a mouse
  wheel scrolls. Keys come from the on-screen keyboard or a hardware keyboard: Ctrl is Control, Alt
  is Option, Meta is Command.
- **Windows and apps**: the Mac's windows with live thumbnails, the Desktop, and a searchable list
  of the Mac's apps to open.

> **Early software.** Oriel has not had a security review yet. A paired device can see your
> Mac's screen and control it, so pair only devices you trust, and keep Sill's Require pairing on.
> The "development host" option sends everything unencrypted: use it only with a test `SillHost`.

Not yet: QR-code pairing, the remote door's pairing link, the Mac's menus, clipboard, trackpad
gestures, host settings, cursor drawing, reconnecting after sleep, and layouts for foldables and
tablets.

## Layout

| Package | What's in it |
|---|---|
| `protocol` | Sill's wire format and JSON payloads, the pairing crypto, SPKI fingerprints and the self-signed certificate (ports of upstream's `Sources/StreamProtocol`) |
| `net` | This device's identity (a P-256 key wrapped by the Android Keystore), TLS 1.3 with pinning and ALPN, pairing, Bonjour discovery, saved Macs |
| `session` | One live session: reader and writer threads, pings and stats, the HEVC decoder, the touch and keyboard view, key mapping |
| `ui` | The Compose screens: the Macs list, pairing, the stream |

## Building and testing

Open the project in Android Studio, or:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The unit tests check the protocol against upstream's own test vectors (pairing proofs, PBKDF2 key,
Mac ID, Bonjour recognition tag) and the exact JSON shapes the Mac expects.

To try it without a real Mac session, build upstream's host and run it with a test pattern (from a
clone of the Sill repository):

```bash
swift build -c release --product SillHost
.build/release/SillHost --synthetic --pairing
```

It prints a pairing code. Find its port with `lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>`. In the
Android emulator, add the Mac by address as `10.0.2.2` and that port. A synthetic host is not
advertised over Bonjour.

## License

Oriel is licensed under the [Apache License 2.0](LICENSE). It ports parts of Sill's code, also
Apache-2.0; [NOTICE](NOTICE) says which.

Found a security problem? Please report it privately through GitHub's security advisories for
this repository, not in a public issue.
