# Fire Remote

Phone remote for a Fire TV Stick over Wi-Fi (ADB). Buttons: power, mute, type-on-TV,
YouTube, Netflix, D-pad + OK, back, home, menu, rewind/play-pause/fast-forward, volume.

## 1. Prep the Fire Stick
- Settings → My Fire TV → About → click the device name 7 times (enables Developer Options)
- Settings → My Fire TV → Developer Options → ADB Debugging: ON
- Settings → My Fire TV → About → Network: note the IP address
- Settings → Display & Sounds → HDMI CEC Device Control: ON (lets Power turn the TV on/off)
- Tip: give the Fire Stick a fixed IP (DHCP reservation) in your router so it never changes

## 2. Build & install
1. Install Android Studio and open this folder (File → Open). Let Gradle sync.
2. On your phone: enable Developer Options and USB debugging, plug it in.
3. Press Run ▶. The app installs on your phone.
   (Or Build → Build App Bundle(s)/APK(s) → Build APK(s) and copy the APK across.)

## 3. First connection
Enter the Fire Stick's IP. The TV will show "Allow USB debugging?" –
tick "Always allow from this computer" and press OK with the Fire Stick remote.

## Notes
- Phone and Fire Stick must be on the same Wi-Fi.
- Volume/mute only work if the Fire Stick is set up to control your TV
  (Settings → Equipment Control). Otherwise use the TV's own remote for volume.
- Each press takes ~0.3–0.5 s – that's Fire OS processing the command.
