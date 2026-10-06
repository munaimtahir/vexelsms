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

## Server credential setup

A dedicated account exists: vexel-fcm-sender@vexelsms.iam.gserviceaccount.com. It has a project custom role with only cloudmessaging.messages.create. No private key has been generated or downloaded.

When ready, create the production key directly at the final path on the Vexel production server (not on a development machine):

    sudo install -d -o munaim -g munaim -m 0700 /etc/vexelhms/secrets
    gcloud iam service-accounts keys create /etc/vexelhms/secrets/firebase-fcm.json \\
      --iam-account=vexel-fcm-sender@vexelsms.iam.gserviceaccount.com \\
      --project=vexelsms
    sudo chown root:munaim /etc/vexelhms/secrets/firebase-fcm.json
    sudo chmod 0640 /etc/vexelhms/secrets/firebase-fcm.json
    sudo chown root:munaim /etc/vexelhms/secrets
    sudo chmod 0750 /etc/vexelhms/secrets

Then add these values to the private Vexel service environment file at /home/munaim/srv/apps/vexelhms/.env:

    FCM_PROJECT_ID=vexelsms
    GOOGLE_APPLICATION_CREDENTIALS=/etc/vexelhms/secrets/firebase-fcm.json

Restart only vexelhms.service. Never copy this server key into the Android repository or APK, and never put its contents in documentation or logs. FCM token registration is sent by the paired device over its existing authenticated gateway heartbeat; it is not used to authenticate to Vexel.
