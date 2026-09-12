# Known issues

A running log of things found while building, that are **not** being fixed in
the change that found them — either because the code is frozen, because the fix
belongs to a feature we have not opened yet, or because the right moment to
reconcile it is the next time someone is already in that file.

The rule for this file: an entry is written the moment it is noticed, with
enough detail that whoever picks it up does not have to rediscover it. An entry
is deleted when it is fixed, and the commit that fixes it says so.

Status: **open** unless a later line says otherwise.

---

## 1. Field order differs between the app and the web client

**Where** `app/shared/.../capture/FormModel.kt` (`toFormFields`) and
`backend/src/main/resources/static/app/screens/capture.js` (`captureForm`).

**What** Both clients generate the capture form from the type's `field_schema`,
so they always agree on *which* fields exist, what they are called, which are
required and what they accept. They order them differently:

- **App** — merges the re-labelled columns and the type's attributes into one
  list and sorts the whole thing by `sort`.
- **Web** — renders every re-labelled column first, then every attribute, so
  `sort` only orders within each of those two groups.

**Which is right** The app. The numbers in the schema plainly intend an
interleave: a Fixed Deposit's `interest_rate` is sort 30, between `Principal`
at 20 and `Opened on` at 40, and Principal → rate → dates is the order someone
reads off an FD receipt. The web pushes the rate below both dates.

**Why it is still here** The fix is a change to frozen-adjacent web code, and
it is worth making deliberately rather than as a side effect of an app stage.
This is an intended, known divergence until then.

**When to fix** The next time we open `capture.js` for its own reasons. Sort the
merged list the way `toFormFields` does; there is no API change and no data
change.

**Risk if left** Cosmetic only. Nothing that reaches the server can differ.

---

## 2. `POST /api/v1/auth/otp/verify` returns 500 for a missing `phone`

**Where** `backend/.../auth/AuthController.kt` (`OtpVerifyBody`).

**What** Omitting the `phone` field returns

```
500  {"error":{"code":"internal_error","message":"Something went wrong on our side…"}}
```

The cause is `HttpMessageNotReadableException` wrapping Jackson's
`MissingKotlinParameterException` for a non-nullable Kotlin constructor
parameter. It escapes the validation handler and lands in the generic 500 path.

**What it should be** A 400 naming the field, like every other bad request the
API answers.

**Why it is still here** v1 is frozen, and no client hits this: both the web
client and the app always send `phone`. Found by a hand-written curl probe.

**When to fix** The next backend pass. Adding an
`@ExceptionHandler(HttpMessageNotReadableException::class)` that maps a missing
or unreadable body to a 400 would cover this and every sibling body type at
once. It is additive — a status changing from 500 to 400 on a malformed request
is not a v1 contract break.

**Risk if left** Low. It misreports a client error as a server error, which
costs someone debugging time and makes a genuine outage harder to spot in logs.

---

## 3. A static-asset change needs a service-worker version bump

**Where** `backend/src/main/resources/static/sw.js` (`VERSION`).

**What** The shell is cached cache-first, deliberately (docs/17), so an edit to
`base.css`, `app.js` or any other shell asset is invisible to a browser that
has already installed the worker until `VERSION` changes. During development
this reads as "my change did nothing" — it cost a build and a wrong diagnosis
while building the phone navigation.

**Why it is still here** It is the cache strategy working as designed, not a
defect. Writing it down is cheaper than rediscovering it.

**When to fix** Not a fix — a habit. Bump `VERSION` in the same commit as any
shell asset change. If this keeps biting, the durable answer is to derive the
version from a build property rather than a hand-edited constant.

**Risk if left** Development friction only. Released builds are fine, because a
release always changes the version.

---

## 4. Screenshot and recents protection is off in debug builds

**Where** `app/androidApp/.../MainActivity.kt`.

**What** `FLAG_SECURE` keeps a family's net worth out of the recents thumbnail
and out of screenshots. It is applied only when `!BuildConfig.DEBUG`, because
it also blacks out `adb exec-out screencap`, and a stage whose evidence is a
black rectangle cannot be reviewed.

**Why it is still here** Deliberate, and the release path is correct. What is
*unverified* is the release path: every screenshot in the review of this stage
is from a debug build, so the flag has been reasoned about rather than seen
working.

**When to fix** Not a fix — a verification. Build once with
`assembleRelease`, confirm `screencap` returns black and the recents card shows
a blank app, then record that here and delete this entry.

**Risk if left** None in production. The risk is only that we believe something
we have not watched happen.

---

## 5. The iOS half of the security seam is declared, not written

**Where** `app/shared/src/iosMain/.../security/Platform.ios.kt`.

**What** `PlatformHost`, `createTokenStore` and `createAppLock` exist for the
iOS target so common code compiles against them, and the two factories throw.
The Android implementations are real; the iOS ones are a Keychain store
(`kSecClassGenericPassword`, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`,
`SecAccessControl` with `.biometryCurrentSet` to match the Android key's
binding) and an `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` lock,
which is Face ID or Touch ID with the passcode behind it — the same pairing
Android gets from `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`.

**Why it is still here** The iOS stage has not been opened, and nothing in
`iosMain` has ever been compiled: that needs the Kotlin/Native toolchain, which
is not installed on this machine by choice.

**When to fix** The iOS stage. Two files in `iosMain` and four lines in the
Swift entry point; nothing above the seam moves.

**Risk if left** None today — no iOS build exists to run it. The entry points
throw with a message pointing here rather than failing silently.
