# WakeProof

An Android alarm that won't stop until you do your push-ups and make your bed.

## How it works
1. Alarm rings (optionally starting quiet and ramping up).
2. Tap **Start** → sound stops.
3. Put the phone face-up on the floor under your face. Each time your face comes close to the top of the screen (proximity sensor) counts as one push-up.
4. After the push-ups you get a few quiet minutes to make your bed, then take a photo. With a Claude API key, Claude checks the bed is actually made; without one, any fresh photo counts.
5. Done → alarm off, streak goes up.

The sound comes back if you stop doing reps for too long, leave the alarm screen, lock the phone, or go over the total time limit. Back button is blocked, and opening the app's settings while it rings just sends you back to the alarm.

## Getting it on your phone
Every push to `main` builds the app on GitHub and publishes it under **Releases**.
Latest download: `https://github.com/<you>/<repo>/releases/latest/download/WakeProof.apk`

On the phone: open that link → tap the file → allow "Install unknown apps" for your browser when asked → Install. New versions install over the old one.

## First-time setup on the phone
Open WakeProof and tap through the red **Finish setup** buttons (notifications, full-screen alarms, battery). Then set your time and days, tap **Save**, and try **Test alarm now**.

## Limits (honest)
- You can still force-stop or uninstall it from Android settings. That's the only escape hatch.
- The push-up counter trusts the proximity sensor — waving a hand over it would count. It's built to make cheating more effort than just doing the push-ups.
