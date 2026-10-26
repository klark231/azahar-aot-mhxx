# First-build checklist

Places most likely to need a fix on the first compile or run, in order:

1. **Azahar API drift** (`jni/native.cpp`, `jni/emu_window_android.cpp`): `Core::System::Load`'s
   signature, `GetAndResetPerfStats` field names, `Frontend::EmuWindow` virtuals
   (`MakeCurrent`, `DoneCurrent`), `RegisterOnInitCallback`.
2. **`config.cpp` on Android**: written for the PC; guarded for the `SeparateWindows` layout
   only. `Apply()` assigns `AudioCore::SinkType::SDL2` / `resolution_factor`; `native.cpp`
   overrides both afterwards (this Azahar counts resolution in percent).
3. **Storage**: the core's Android file layer expects `org.mh4u.port.Storage`'s static
   methods (`common/android_utils.h`). The user folder is the app's external files folder; the
   user folder path inside the core is the virtual root `/`, so any `std::filesystem`/`std::ifstream`
   use on a user-folder path needs `AndroidUtils::TranslateFilePath` (done for `hooks.cpp`
   options and `frontend.cpp` library staging; check `netsync.cpp` and others as they show up).
4. **Game path**: stored as `!<absolute path>` (the core's "already native" marker) to the copy
   in `files/games/`.
5. **Rooms before the game**: `App::Multiplayer::StartAtBoot` joins/hosts from `mh4u-app.ini`
   (`join`, `host`) when the game starts, as on PC; the live Join/Host/Leave calls are only
   used while the game runs.
6. **Vulkan**: the system driver unless the player imports one (Settings > Display > GPU
   driver; libadrenotools, Adreno only). The Adreno GPU boost and the CPU core choice are there
   too. First build: `adrenotools` must be a CMake target (Azahar's externals, arm64) and its hook
   libraries must end up in the APK's native library folder (`extractNativeLibs` is on).
