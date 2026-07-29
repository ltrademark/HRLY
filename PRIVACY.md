# Privacy Policy for HRLY

**Effective date:** July 29, 2026

HRLY (package name `com.ltrademark.hourly`) is a minimalist hourly chime app published by Ltrademark. This policy explains what the app does and does not do with your information.

## Summary

**HRLY collects no data whatsoever.** It has no user accounts, no analytics, no advertising, no crash reporting, and no third-party SDKs. Nothing you do in the app is transmitted anywhere, because the app has no ability to transmit anything.

## No network access

HRLY does not request the `INTERNET` permission. Android therefore prevents the app from making any network connection at all, whether to us or to anyone else. This is not a promise about our intentions, it is a technical property of the app that anyone can verify by inspecting its manifest.

The app contains no networking, analytics, advertising, or telemetry libraries of any kind.

## What is stored on your device

All of the following stays on your device, in storage private to HRLY. None of it is readable by other apps, and none of it is sent anywhere.

- **Your settings.** Chime mode, tone timing, quiet hours, and related preferences are saved in Android's private preference storage for the app.
- **Custom tones.** If you choose your own audio file for a short or long tone, HRLY copies that file into its own private app storage and plays the copy. The app reads the file's name so it can show you which file you selected. It does not retain access to the folder you picked from, and it does not read any other file on your device.

Uninstalling HRLY deletes all of it. There is no server-side copy, because there is no server.

## Permissions and why they exist

| Permission | Why HRLY needs it |
| --- | --- |
| `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` | Chime precisely on the hour. An hourly chime that drifts is useless, so the app must schedule exact alarms. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep running and play the tone while the app is not on screen. |
| `POST_NOTIFICATIONS` | Show the ongoing notification and its skip, pause, and stop controls. |
| `RECEIVE_BOOT_COMPLETED` | Resume chiming after you restart your phone, without you reopening the app. |
| `VIBRATE` | Vibrate the tone pattern in Vibrate or Both mode. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optionally ask you to exempt HRLY from battery optimization so hourly chimes are not delayed or dropped. This is your choice and the app works without it. |

None of these permissions grant access to your contacts, location, camera, microphone, messages, or browsing activity, and HRLY does not request any permission that would.

## Children

HRLY is suitable for all ages and collects nothing from anyone, including children.

## Verifying any of this

HRLY is free software licensed under the GNU General Public License v3. The complete source code is public, so you do not have to take this policy on trust:

<https://github.com/ltrademark/HRLY>

## Changes to this policy

If HRLY ever changes in a way that affects this policy, the policy will be updated in the same repository and the effective date above will change. The revision history is public in the repository's Git log.

## Contact

Questions about this policy can be raised as an issue at <https://github.com/ltrademark/HRLY/issues>.

---

2025-2026 &copy; Ltrademark&reg;.
