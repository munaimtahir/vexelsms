# Vexel SMS Gateway for Android

This private Android app pairs one dedicated Android phone to Vexel Health and sends outbound SMS through its SIM. It does not read incoming messages, contacts, call history, or clinical data beyond the message currently being sent.

## Firebase registration

- Firebase project: **vexelsms**
- Project number / FCM sender ID: **613728031907**
- Registered Android application ID: **pk.vexel.health.smsgateway**
- Firebase Android App ID: **1:613728031907:android:eab22c76b3198adb4b5b16**
- SDK configuration: app/google-services.json (downloaded from Firebase, ignored by Git)
- FCM HTTP v1 API: enabled

The Android application ID pre-existed in the project and was retained. The Firebase project directory has a minimal firebase.json and .firebaserc; no other Firebase products are configured. This app uses Firebase Cloud Messaging only as a content-free wake signal. Without FCM setup, HTTPS polling still runs when the app opens, after reboot, and periodically through WorkManager.

## Build and install

Install Android Studio or Android SDK command-line tools with JDK 17. From this directory run:

    ./gradlew :app:assembleDebug

The debug APK is at app/build/outputs/apk/debug/app-debug.apk. The phone needs Android 8.0 (API 26) or newer and SMS capability. Grant Camera permission when scanning the pairing QR and SMS permission before sending. Select the intended SIM as Android's default SMS SIM.

## Pair a device

In Vexel, open Settings → Communications → SMS Gateway Devices and create a gateway pairing code. On the Android device, install the APK, choose **Scan pairing QR**, and scan the code. You may paste the QR's JSON payload instead. Pairing codes expire after ten minutes and work once. The app exchanges the code for a long-lived device credential and stores it encrypted using Android Keystore. Never share the pairing QR or device credential.

Keep the phone powered and connected to the internet. FCM carries only the data value type=work_available; Android then makes an authenticated HTTPS request to Vexel to claim work. SMS content and patient information are never included in FCM.

## Server credentials and wake observation

The existing production sender key belongs only on the Vexel server at
`/etc/vexelhms/secrets/firebase-fcm.json`. The HMS audit on 14 October 2026 observed
root:munaim ownership and mode 640. The web service has the ADC path; the dispatcher
service must also receive it. Never generate a replacement key for troubleshooting,
copy it into this repository/APK, or print credentials or registration tokens.

Firebase carries only `type=work_available`, without a notification payload. Version
1.1.0 records a local receipt timestamp and shows **Last FCM wake received** in PKT
when the app resumes. This timestamp contains no message or patient data. A successful
server wake request alone does not establish receipt on the phone.

## Recovery changes (DD-027, version 1.1.0)

- A persisted CLAIMED job resumes without claiming another message. Room commits the
  SEND_STARTED acknowledgement and ready state together. Legacy STARTING/acknowledged
  jobs also recover.
- Every ready job replays its original SEND_STARTED event UUID for fresh server
  authorization before the durable INVOKING marker and SmsManager call. Expired,
  cancelled or superseded authorization is archived locally as REVIEW_REQUIRED;
  work/events are retained. Internet errors retry synchronization, not physical SMS.
- INVOKING is never submitted twice. An interrupted invocation becomes UNKNOWN;
  multipart partial success also becomes UNKNOWN. Callback result codes are captured
  before asynchronous processing and error summaries use allowlisted codes.
- Buffered callbacks retain their event UUID across retries. The coordinated HMS
  change acknowledges historical/superseded results without changing a current attempt.
  Staff can review UNKNOWN without retry, allowing unrelated gateway work to continue.
- The Room schema remains version 1; no local data reset or destructive migration.

Five JUnit regressions are in `SendSafetyTest`: rejected authorization, interruption
following the durable marker, reauthorization order, multipart aggregation and late
callback ordering. Android builds/test execution were explicitly deferred by the owner
in this sprint. The previous build was interrupted; neither this APK nor these new
JUnit cases are claimed as built, installed, or green.

Later, run `./gradlew :app:testDebugUnitTest :app:assembleDebug`, then install an update
signed with the SAME key as the current phone app. Use the configured release signing
instead if that is what the phone already runs. Do not uninstall or clear app data to
work around a signature mismatch: retained work and callback evidence matter. Verify
1.1.0 on the paired phone and follow `docs/operations/communications.md` in vexelhms
with an owner-named consenting recipient. Registration-token/FID migration remains
deferred (DD-028). No automatic WhatsApp implementation is included.
