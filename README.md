# Family Appifier

*A 2-hour vibe-coded weekend project to scratch my own itch.*

This project provides a secure, lightweight, and restricted Android WebView wrapper template (specifically designed to complement Google Family Link). It is designed to expose specific local or whitelisted websites (such as a self-signed home server or online school platforms) on children's devices without granting them unrestricted web browsing or consuming general Google Chrome app time limits.

## Features
* **Zero-Code Sub-Apps**: All client application modules are thin shells that inherit functionality from the core library, specifying configurations entirely inside their `AndroidManifest.xml` meta-data.
* **Domain Whitelisting**: Strict, wildcard-supported whitelist enforcement. Standard web requests to unapproved domains are blocked inside the app, showing warnings.
* **Self-Signed SSL Support**: Seamlessly trust custom CA/server certificates on a *per-domain basis* via Android’s **Network Security Configuration**, avoiding system-wide certificate installations and keeping global traffic secure.
* **Session Persistence**: Persistent cookie caching and DOM/Database storage configuration ensures login states are retained across app restarts.
* **Native Downloads**: Automatically routes web download hooks into Android's native `DownloadManager`, complete with session cookie forwardings.
* **External Intent Routing**: Redirects system-specific links (like `tel:`, `mailto:`, or custom intents) to corresponding system applications.
* **Favicon Sync Script**: Automatically downloads your target site's favicon and converts/resizes it for all Android screen densities.

---

## Project Structure

* **`library/`**: The core Android Library module containing `WebViewActivity`, `WebViewClientImpl`, default themes, layout, and logic.
* **`app-immich/`**: An example shell module configuring a wrapper for the Immich home photo/video server.

---

## Quick Start & Setup (for `app-immich`)

### 1. Configure the Start URL and Whitelist
Open [app-immich's Manifest](app-immich/src/main/AndroidManifest.xml) and adjust the metadata properties inside the `<activity>` element:

```xml
<!-- Launch URL -->
<meta-data
    android:name="de.kalass.familyappifier.START_URL"
    android:value="https://immich.home.kalass.de" />

<!-- Comma-separated list of approved domains (supports leading wildcards) -->
<meta-data
    android:name="de.kalass.familyappifier.DOMAIN_WHITELIST"
    android:value="immich.home.kalass.de,*.immich.home.kalass.de" />
```

### 2. Configure Your Custom Self-Signed Certificate
If your site uses a self-signed or local private SSL/TLS certificate:
1. Export your Root CA or server certificate in **PEM format** (e.g., `my_cert.crt` starting with `-----BEGIN CERTIFICATE-----`).
2. Copy it into the app's raw resources:
   ```bash
   cp my_cert.crt app-immich/src/main/res/raw/immich_ca.crt
   ```
3. The XML profile in `app-immich/src/main/res/xml/network_security_config.xml` is pre-configured to trust `@raw/immich_ca` for requests hitting `immich.home.kalass.de`. If your domain changes, update the domain rules in that file.

### 3. Generate App Launcher Icons from Favicon
Make sure your target site is running, then execute the helper script at the root directory:
```bash
./update_icon.py
```
This script:
1. Reads `START_URL` from the manifest.
2. Contacts the site (ignoring self-signed SSL warnings).
3. Finds the highest resolution PNG favicon.
4. Uses macOS's native `sips` tool to generate resized `ic_launcher.png` images in all necessary screen densities inside `app-immich/src/main/res/mipmap-*`.

### 4. Build the App
Compile and assemble the project debug APK:
```bash
./gradlew assembleDebug
```
The resulting APK will be generated at:
`app-immich/build/outputs/apk/debug/app-immich-debug.apk`

---

## How to Add a New Sub-App Module

To wrap another website (e.g., a school portal `https://school.portal.de`):

1. **Create Directory**: Copy the `app-immich` folder to a new directory, e.g., `app-school`.
2. **Include in Settings**: Open [settings.gradle.kts](settings.gradle.kts) and add:
   ```kotlin
   include(":app-school")
   ```
3. **Change Application ID**: Open `app-school/build.gradle.kts` and change:
   - `namespace = "de.kalass.schoolwrapper"`
   - `applicationId = "de.kalass.schoolwrapper"`
4. **Update Manifest**: Open `app-school/src/main/AndroidManifest.xml` and change:
   - The launcher metadata URLs and whitelists.
   - The `@string/app_name` or resources references.
5. **Adjust SSL Config** (Optional): If the site uses standard public HTTPS, you can delete `app-school/src/main/res/xml/network_security_config.xml` and remove `android:networkSecurityConfig` from the manifest. If it uses a custom SSL cert, place it in `app-school/src/main/res/raw/school_ca.crt` and update `network_security_config.xml` accordingly.
6. **Generate Icon**: Run `./update_icon.py` (it will work similarly if configured for that module).

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

