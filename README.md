# zuiFont

Change the system font on Lenovo ZUI/ZUXOS devices without ROOT. The font switches immediately across the whole system.

## Features

- Apply any `.ttf` / `.otf` file as the system font
- Restore the default system font with one tap

## Requirements

- A Lenovo ZUI/ZUXOS device
- Android 10 or later (minSdk 29)

Tested on:

| Model | ROM |
|---|---|
| TB323FU | ZUI 18.0 |
| TB324ZC | ZUXOS 2.0 |

## Install

Download the APK from [Releases](https://github.com/miner7222/zuiFont/releases) and install it on the device.

## Usage

1. Tap **Select file (.ttf / .otf)** and pick a font file.
2. Tap **Apply font**.
3. A snackbar shows the result once the change is complete.
4. Tap **Restore default font** to go back to the system default.

You can also share or open a font file from any file manager to preselect it in the app.

## Build

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- The change is system-wide and survives reboots until changed again.
- Only `.ttf` and `.otf` files are accepted.

## License

Licensed under the [Apache License 2.0](LICENSE).
