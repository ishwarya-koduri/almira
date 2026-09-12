# app — the Kotlin Multiplatform client

One Kotlin codebase, two platforms. Everything real lives in `shared`; the two
platform modules exist to hand Compose a window and to answer the questions only
they can answer.

```
app/
├─ shared/       theme, and later the API client, models and crypto
│  ├─ commonMain   what both platforms run
│  ├─ androidMain  Android's half of every `expect`
│  └─ iosMain      iOS's half, plus the UIViewController Swift calls
├─ androidApp/   an Activity and a manifest
└─ iosApp/       an @main struct and an Xcode project
```

## Building

The environment is folder-local and must be sourced first — it points Gradle,
the Android SDK and the Kotlin/Native toolchain at `~/Developer/almira-personal`
rather than at your home directory:

```bash
source ~/Developer/almira-personal/env.sh
cd app
./gradlew :androidApp:assembleDebug
```

The API it talks to defaults to `http://10.0.2.2:18080` — the host's loopback as
seen from an Android emulator, which is where `dev-personal/up.sh` serves. Point
it elsewhere without editing anything:

```bash
./gradlew :androidApp:assembleDebug -Palmira.apiBaseUrl=http://192.168.1.20:18080
```

On iOS the same value is read from `Info.plist`, so the simulator and a physical
device can differ without a code change.

## The theme is the design system, not a copy of it

`shared/…/theme/` is [docs/02](../docs/02-ux-and-design-system.md) expressed as
Kotlin: the same hex values as `tokens.css`, the same 4px spacing scale, the
same 1.2 modular type scale, and the rule that numbers are the hero so amounts
take the serif.

It provides two things at once. Almira's own tokens — `AlmiraTheme.colors`,
`.typography`, `.spacing`, `.radii` — are what product code should use, because
Material has no slot for a hairline or for a gold used exactly once per screen.
And a Material 3 scheme *derived* from those tokens, so a stock `Button` or
`Card` already looks like Almira without being restyled at the call site. Leaving
out that second half is how a design system quietly dies.

The faces are still the platform serif and sans. Fraunces and Inter are two
variable fonts, about 400 KB with licence files to ship alongside; bundling them
changes `AlmiraTypography.kt` and nothing else.

## iOS

The scaffold is committed and the Kotlin targets are configured, but **nothing
has been compiled for iOS**: that needs Xcode, which is a system-level install
awaiting approval. `iosApp.xcodeproj` was written by hand and validated
structurally with `plutil`; the first time Xcode opens it is the first real test
of it.
