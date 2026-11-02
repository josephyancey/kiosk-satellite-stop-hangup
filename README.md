# Stop Hangs Up Calls

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that ends an intercom call when you say "stop". Say it on its own during a call and the call hangs up. Saying it in the middle of a sentence does nothing. Only this kiosk's microphone counts, so "stop" coming through its speaker from the other end doesn't trigger it here. If the other kiosk runs the plugin too, someone saying "stop" there hangs it up, which ends the call for both of you.

It hangs up a call you are placing, a live call, and an announcement going out from or playing on this kiosk: the same calls the hang-up button ends. Outside a call, "stop" does what it always does.

## Installation

### Community Plugins (Recommended)

Stop Hangs Up Calls is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=josephyancey/kiosk-satellite-stop-hangup)

Or search for `Stop Hangs Up Calls` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/josephyancey/kiosk-satellite-stop-hangup` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Setup

1. Turn on **Stop word interruption** under **Voice Satellite > Wake Word**. The plugin uses the kiosk's own stop word.
2. Turn on **Remote Administration** under **Device**, with a password. The plugin talks to it on this kiosk (see [How it works](#how-it-works)).
3. Enable the plugin and enter the remote admin password in its settings. The status line says **Ready** once it has logged in.

With a fleet, install it on the leader and add it to the profile's **Plugins** row. If every kiosk has the same admin password, turn on **Plugin secrets** under the profile's Credentials so the password goes along too. Each kiosk still needs its own **Enable Plugins** switch on.

## Settings

| Setting | What it does |
| --- | --- |
| Remote admin password | This kiosk's remote admin password. Stored as a secret, masked in the Plugin Manager |
| Remote admin port | The remote admin port, 2324 unless you changed it |
| Remote admin uses HTTPS | Turn on if remote admin is set to HTTPS. The kiosk's own certificate is accepted, since the plugin only ever talks to 127.0.0.1 |

## How it works

Kiosk Satellite only listens for the stop word while something asks it to, such as a ringing timer, and the plugin API has no way to ask, or to hang up a call. Remote admin has both, as the commands `setStopWordArmed` and `intercomHangup`. So the plugin:

1. Follows the intercom state. When a call is placed, goes live or an announcement starts, it asks this kiosk to listen for the stop word, and stops asking when the call ends. A request that fails is sent again until the kiosk has it, so the stop word is never left listening after a call. If the dashboard reloads during a call, which turns the stop word off, it asks again.
2. Hangs the call up when the stop word is heard during a call.

Both requests go to remote admin on this kiosk itself, at `127.0.0.1`, logged in with a token from the password. Nothing leaves the device.

## Limits

- It depends on remote admin being on, and on the two commands above, which are not part of the documented plugin API. A future Kiosk Satellite release could change them; the status line then shows the error, and calls keep working as before.
- Keep remote admin on while the plugin is enabled. With it off, another app on the device could take its port and be sent the password.
- Changing the admin password needs the new one entered here too. After a wrong password the plugin stops trying, so remote admin does not lock this kiosk out, until you enter a new one or the plugin restarts.

## Build and test

Install Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools. Set `ANDROID_HOME` and `JAVA_HOME` as needed.

```sh
python3 tools/test.py
python3 tools/build.py
```

The build writes `dist/stop-hangup-<version>.zip`. For local testing, install it with **Plugin Manager > Developer Tools > Install from ZIP**. Without a local SDK, `build.sh` runs both inside the `ghcr.io/cirruslabs/flutter` image:

```sh
docker run --rm -v "$PWD:/src" ghcr.io/cirruslabs/flutter:3.44.0 sh /src/build.sh
```

The SDK sources in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin SDK](https://github.com/jxlarrea/kiosk-satellite-plugins).

## License

[Apache-2.0](LICENSE). Kiosk Satellite has its own application license. The handset in the icon is `call_end` from Google's [Material Icons](https://github.com/google/material-design-icons), also Apache-2.0.
