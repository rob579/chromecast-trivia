# Chromecast Trivia

A personal Android TV app for Chromecast with Google TV: plays a YouTube
trivia quiz video with the current round name and the team's answers on screen.

## Status

First test build: video player and remote controls only, with one fixed video.
The round and answers panels are placeholders.

## Remote

| Button | Action |
| --- | --- |
| OK (centre) | Play / pause |
| Left / Right | Back / forward 10 seconds |
| Down / Up | Back / forward 1 minute |
| Back | Exit |

## Getting the APK

Every push to `main` is built by GitHub Actions. The newest build is always
`trivia.apk` on the **Latest build** release (Releases, right-hand side of the
repo page).

## Installing on the Chromecast

1. On the Chromecast: Settings > System > About, click "Android TV OS build"
   seven times to enable developer options.
2. Settings > System > Developer options: turn on USB debugging.
3. Find the Chromecast's IP address under Settings > Network & Internet.
4. From a computer with adb installed:

   ```
   adb connect <chromecast-ip>
   adb install -r trivia.apk
   ```

The app appears as "Trivia" in the apps list.
