# Kravos Player

Personal Android music player that streams MP3 and FLAC straight from Google Drive.
Built in the spirit of CloudPlayer's paid tier, plus a practice mode for learning bass lines.

## Features

- **Google Drive library**: pick one or more Drive folders (My Drive or "Shared with me"); the app reads
  ID3v2.2/2.3/2.4, ID3v1 and FLAC Vorbis comments with HTTP range requests (only the tag bytes,
  not whole files) and builds Artists / Albums / Songs / Genres / Folders views.
- **Album art**: `cover.jpg` / `folder.jpg` / `front.*` from the album folder, otherwise the embedded picture.
- **Old ex-YU rips**: ID3v1/Latin-1 tags that are really Windows-1250 (Č Ć Š Ž Đ) are detected automatically.
- **Playback**: Media3/ExoPlayer, gapless, background playback with lock-screen and notification controls,
  shuffle, repeat, queue editing, playlists, smart playlists (recently added, most/recently played, downloaded).
- **Offline**: download songs, albums, artists or playlists; downloads never get evicted. Plus an LRU
  streaming cache (512 MB to 8 GB) so replays don't hit Drive again.
- **Chromecast**: the phone runs a small LAN proxy with a random per-session secret in every URL,
  so the Cast device can stream Drive files without your OAuth token ever leaving the phone.
- **Internet radio**: Serbian and worldwide stations from the radio-browser.info directory, favorites,
  add any stream by URL (.pls/.m3u resolved), quality and "casts on its own" filters, live now-playing titles.
  HTTPS stations are handed straight to the Chromecast, which keeps playing after the phone leaves Wi-Fi;
  HTTP-only stations are relayed through the phone. When a Cast session drops, the phone never starts
  playing on its own speaker.
- **Drive browser**: walk the whole Drive (My Drive / Shared with me), search by name, play any file or a
  whole folder including subfolders without scanning, add a folder to the library with one tap.
  The library Folders tab can search folders and play/shuffle a folder with all its subfolders.
- **Podcasts**: search Apple's podcast directory or browse the Serbian / world top charts, subscribe, or add any show by
  its RSS address. Episodes can be downloaded for offline listening (same never-evicted store and Wi-Fi-only rule as
  songs; Downloaded section on the Podcasts home). Every episode remembers where you stopped (Continue listening on the Podcasts home), speed is
  remembered per show (or one speed for all), with back 10 s / forward 30 s. Sleep timer and Chromecast work the same as
  for music; HTTPS episodes are fetched by the Chromecast itself. The tab remembers where you were (a show, the search
  page, scroll position) when you switch to Library or Radio and come back.
- **Sleep timer** with fade-out, or stop at the end of the current song.
- **Practice mode**: tempo 0.5×–1.5× without pitch change, pitch shift ±12 semitones, and an A–B loop
  with ¼-second nudges.

Privacy: the app only requests `drive.readonly`, talks only to Google, and has no server of its own.

## One-time Google Cloud setup

1. <https://console.cloud.google.com> → create a project.
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **Google Auth Platform** (OAuth consent screen) → Get started → External.
4. **Data Access** → add scope `https://www.googleapis.com/auth/drive.readonly`.
5. **Audience** → add your Google account as a test user, then **Publish app**. (In "Testing"
   mode Google expires the grant every 7 days. Published-but-unverified only shows a one-time
   "Google hasn't verified this app" screen: Advanced → Go to Kravos Player.)
6. **Clients → Create client → Android**
   - Package name: `net.kravos.player`
   - SHA-1: `64:88:35:18:51:4B:1A:A3:94:EC:93:E4:2D:A5:15:56:57:DF:E5:5E`

No client ID or secret goes into the app: Google matches the app by package name + signing certificate.

## Building

CI (`.github/workflows/build.yml`) builds a signed release APK on every push to `main` and attaches
it to a GitHub Release (`build-N`). The signing keystore is stored encrypted at
`signing/release.jks.enc`. CI needs one repository secret, `KP_SIGNING_PASS`.

Local build:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in signing/release.jks.enc -out /tmp/release.jks
export KP_KEYSTORE_FILE=/tmp/release.jks KP_KEYSTORE_PASSWORD='<password>'
./gradlew assembleRelease
```

Keep the keystore password safe. The OAuth client is tied to this key's SHA-1, and Android only
installs updates that are signed with the same key.

## Updates

The app checks this repo's latest GitHub release (`build-N`) on start (every few hours) and in
**Settings → App updates**; a bar above the player offers Update → Install. This needs the repo to be
public (the app has no GitHub token). Every build is signed with the same key, so it installs over the old one.

## Install

Download the APK from the latest Release on the phone and open it (allow "Install unknown apps" for
your browser/Files app). Play Protect may say the app is unrecognised: choose *Install anyway*.
