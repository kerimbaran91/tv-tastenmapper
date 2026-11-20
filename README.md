# TV-Tastenmapper

Belegt die **Xiaomi-TV+-Taste** der Fernbedienung (Xiaomi TV Stick) auf **Play/Pause** um.

## Stabilität
- Bedienungshilfe-Dienst, der jeden Fehler abfängt (Android schaltet ihn deshalb nicht ab)
- **Selbstheilung**: Mit der einmalig per ADB erteilten Berechtigung `WRITE_SECURE_SETTINGS` schaltet die App ihren Dienst selbst wieder ein
- Prüfung nach jedem Neustart und App-Update, zusätzlich alle 15 Minuten

## Einrichtung mit Termux (Samsung S23)

1. **Am Stick:** Einstellungen → Geräteeinstellungen → Info → 7× auf „Build“ tippen.
   Dann Einstellungen → Geräteeinstellungen → Entwickleroptionen → **USB-Debugging** einschalten.
   IP-Adresse unter Einstellungen → Netzwerk & Internet → WLAN ansehen.
2. **In Termux:**
   ```
   pkg install android-tools
   adb connect 192.168.178.XX
   ```
   Am Fernseher „Zulassen“ bestätigen (Haken bei „Immer zulassen“).
3. **App installieren** (APK vorher in Termux-Ordner kopieren, z. B. mit `termux-setup-storage`):
   ```
   adb install -r ~/storage/downloads/TV-Tastenmapper.apk
   ```
4. **Selbstheilung freischalten:**
   ```
   adb shell pm grant de.kerim.tvmapper android.permission.WRITE_SECURE_SETTINGS
   ```
5. App am Stick öffnen → **Dienst aktivieren** → **TV+-Taste anlernen** → TV+ drücken. Fertig.

## Build
GitHub Actions baut bei jedem Push die signierte APK (Release + Branch `apk`).
