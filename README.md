# TV-Tastenmapper

Belegt die **Xiaomi-TV+-Taste** der Fernbedienung (Xiaomi TV Stick) auf **Play/Pause** um.

## Stabilität
- Bedienungshilfe-Dienst, der jeden Fehler abfängt (Android schaltet ihn deshalb nicht ab)
- **Selbstheilung**: Mit der einmalig per ADB erteilten Berechtigung `WRITE_SECURE_SETTINGS` schaltet die App ihren Dienst selbst wieder ein
- Prüfung nach jedem Neustart und App-Update, zusätzlich alle 15 Minuten

## Zielgerät
Xiaomi TV Stick 4K (2nd Gen) mit Google TV / Android 14. Google TV sperrt die Bedienungshilfe für
nachinstallierte Apps – deshalb erfolgt die Einrichtung per ADB; die App schaltet ihren Dienst dann selbst ein.

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
4. **Selbstheilung und Updates freischalten:**
   ```
   adb shell pm grant de.kerim.tvmapper android.permission.WRITE_SECURE_SETTINGS
   adb shell appops set de.kerim.tvmapper REQUEST_INSTALL_PACKAGES allow
   ```
5. App am Stick öffnen (schaltet den Dienst selbst ein) → **Tasten-Diagnose** → TV+ drücken.
   Steht dort `[Dienst]`, lässt sich die Taste umbelegen.
6. **TV+-Taste anlernen** → TV+ drücken. Fertig.

## Updates
In der App unter **Updates** → „Nach Updates suchen“. Die App holt die neueste Version von GitHub und öffnet den Installer.
Falls die Installation nicht erlaubt ist, einmalig:
```
adb shell appops set de.kerim.tvmapper REQUEST_INSTALL_PACKAGES allow
```

## Build
GitHub Actions baut bei jedem Push die signierte APK (Release + Branch `apk`).
