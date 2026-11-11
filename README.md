# Chromecast Trivia

A personal Android TV app for Chromecast with Google TV: plays a YouTube
trivia quiz video with the current round name and the team's answers on screen.

## How it works

The app runs a small web page on the Chromecast itself. Scan the QR code on
the TV with a phone on the same Wi-Fi to:

- paste the YouTube link for the quiz video
- set up rounds (name and number of questions)
- choose the current round
- type answers, which appear on the TV as you type
- play, pause and skip

The quiz and the video position are remembered if the app is closed.

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
2. Settings > System > Developer options: turn on Wireless debugging.
3. Find the Chromecast's IP address under Settings > Network & Internet.
4. From a computer with adb installed:

   ```
   adb pair <chromecast-ip>:<pairing port>     (first time only; code from
                                               Wireless debugging > Pair device)
   adb connect <chromecast-ip>:<port>          (port from the Wireless debugging screen)
   adb install -r trivia.apk
   ```

The app appears as "Trivia" in the apps list.
