# Windows and Android clients

Both applications use the same authenticated Translator REST API as the browser.
They let you select a model, enter language codes or names, detect the source
language automatically, and request alternatives and examples. You can copy the
main translation and see the estimated cost of the last result and the recorded
total for your account. The interface is in Russian.

Start the server using the [backend instructions](../README.md) and sign in with
an existing account. Use the browser interface to create accounts as an administrator
or change your password. The apps save the refresh session and restore it after
relaunching. Passwords and access tokens are kept only in memory. Use **Выйти**
to remove the saved login and revoke its refresh session on the server.
Each login creates an independent session. An account can have any number of
active sessions across clients. Signing out of one session preserves the others.
The existing server refresh-session lifetime applies, 30 days by default. An
expired or revoked session requires another sign-in. A lost refresh response
also requires sign-in because the single-use token may already have been consumed.
The apps accept server origins such as `https://translator.example:8443`.
The OpenAI key stays on the server.

## Windows

From the repository root in PowerShell, with JDK 21 on `PATH`:

```powershell
.\mvnw.cmd -f clients/windows/pom.xml verify
java -jar clients/windows/target/app/translator-windows.jar
```

The address defaults to `http://localhost:8080` for a local server. For a remote
server, enter its HTTPS address. HTTP is allowed only for localhost and the
loopback IP literals `127.0.0.1` and `[::1]`. In the source text field,
**Ctrl+Enter** starts translation.

Windows saves sessions under `%LOCALAPPDATA%\Translator\sessions`, encrypted with
Windows DPAPI for the current Windows user. Each running window exclusively owns
its saved session. A new window restores an available saved session. When all
saved sessions belong to running windows, the new window accepts another login.
Closing a window preserves its session for the next launch. **Выйти** ends only
that window's session.

To build a portable Windows application with an embedded Java runtime, run on Windows:

```powershell
jpackage --type app-image --name Translator --app-version 0.1.0 `
  --input clients/windows/target/app --main-jar translator-windows.jar `
  --dest clients/windows/target/windows
```

Open `clients/windows/target/windows/Translator/Translator.exe`. Keep the whole
`Translator` folder together when moving or sharing the application image. To
build a new image, choose a destination without an existing `Translator` folder.

## Android

The app requires Android 8.0 or later. To build it, use JDK 17 or newer and
install Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to the
SDK directory or open `clients/android` as a project in Android Studio.
To install from the command line, add Android SDK Platform-Tools to `PATH`.

```powershell
Set-Location clients/android
.\gradlew.bat assembleDebug lintDebug
adb install -r build/outputs/apk/debug/translator-android-debug.apk
```

The debug build defaults to `http://10.0.2.2:8080`, which reaches the development
machine's loopback interface from the Android Emulator. On a physical device,
enter a server HTTPS address that the device can reach and whose certificate it
trusts. The release build requires HTTPS. To distribute a release APK, configure
signing with your Android signing key in Android Studio or your build pipeline.

Android encrypts the saved session with an AES-GCM key in Android Keystore and
stores it in app-private preferences, excluded from cloud backup and device
transfer. Closing or recreating the activity preserves the login. Removing app
data or uninstalling the app removes the saved session.

The common Java sources in `shared/src/main/java` are compiled into both apps.
The Windows `verify` build exercises the API client against a local HTTP stand-in.

Build tool references: [Android Gradle Plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Android Emulator networking](https://developer.android.com/studio/run/emulator-networking-address),
and [JDK 21 packaging](https://docs.oracle.com/en/java/javase/21/jpackage/basic-packaging.html).
