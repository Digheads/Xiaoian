# 05 — Display Renderer Integration

## Overview

The Xiaoian app must embed two display renderers that replace the standalone Anland.Termux and Termux:X11 apps:

| Renderer | Protocol | Source App | Used By |
|---|---|---|---|
| `AnlandSurfaceView` | Wayland | Anland.Termux (`com.anland.termux`) | KDE Plasma 6 variant |
| `X11SurfaceView` | X11 | Termux:X11 (`com.termux.x11`) | XFCE 4 variant |

Both renderers live inside `DisplayActivity`, which is launched on the target display.

## DisplayActivity

```kotlin
class DisplayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide system UI for immersive desktop experience
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val displayMode = intent.getStringExtra("display_mode") ?: "x11"

        val rendererView: View = when (displayMode) {
            "wayland" -> AnlandSurfaceView(this).apply {
                connectToSocket("$tmpDir/anland/display_daemon.sock")
            }
            "x11" -> X11SurfaceView(this).apply {
                connectToSocket("$tmpDir/.X11-unix/X0")
            }
            else -> throw IllegalArgumentException("Unknown display_mode: $displayMode")
        }

        setContentView(rendererView)
    }
}
```

### Launching on External Display

```kotlin
// From XiaoianService:
fun launchDisplay(displayId: Int, mode: String) {
    val intent = Intent(this, DisplayActivity::class.java).apply {
        putExtra("display_mode", if (de == "kde") "wayland" else "x11")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 0x18000000)
    }

    val options = ActivityOptions.makeBasic()
    options.launchDisplayId = displayId
    startActivity(intent, options.toBundle())
}
```

This is equivalent to the shell script's:
```bash
am start --display "$EXTERNAL_DISPLAY_ID" \
    -n com.xiaoian.app/.DisplayActivity \
    --es display_mode wayland \
    --windowingMode 1 -f 0x18000000
```

---

## X11 Renderer (Termux:X11 fork)

### As built (supersedes the plan below)

The plan below assumed a separate `termux-x11` server in `$PREFIX` and a custom `X11SurfaceView`. The implementation took a different route:

| Plan | As built |
|---|---|
| `termux-x11` server process from Termux `$PREFIX/bin` | X server started from the APK: `CLASSPATH=<apk> app_process /system/bin com.termux.x11.CmdEntryPoint :0` (as root, from the script) |
| Fork only `LorieView` + renderer into a new `X11SurfaceView` | Whole Termux:X11 frontend embedded as the `lorie` library module (`com.termux.x11.MainActivity`, prefs, extra keys) |
| Build native code with NDK/CMake | Prebuilt `.so` files copied from the matching Termux:X11 APK into `lorie/src/main/jniLibs/` — see [update/termux-x11-update.md](update/termux-x11-update.md) |
| `DisplayActivity` with `display_mode` extra | Script launches `com.xiaoian.app/com.termux.x11.MainActivity` directly |

Socket location: the X server takes `TMPDIR` as the container's `/tmp` and derives the container root from its parent (X11 font path). The script passes `TMPDIR=<rootfs>/tmp`, which is the same directory as its own bind-mounted `$INFRA_ROOT/tmp`.

### Source Analysis

The Termux:X11 app (https://github.com/termux/termux-x11) consists of:

```
termux-x11/
├── app/src/main/java/com/termux/x11/
│   ├── MainActivity.java          ← Activity setup, lifecycle
│   ├── LorieView.java             ← Core SurfaceView (rendering + input)
│   ├── InputEventSender.java      ← Sends input events to X server
│   ├── Prefs.java                 ← User preferences
│   └── utils/
├── app/src/main/cpp/
│   ├── lorie/
│   │   ├── compositor.cpp         ← Wayland compositor (server side, NOT needed)
│   │   ├── renderer.cpp           ← EGL/GLES rendering of X framebuffer
│   │   └── input.cpp              ← Input event handling
│   └── CMakeLists.txt
└── common-lib/                    ← Shared native code
```

### What We Need

| Component | Need? | Why |
|---|---|---|
| `LorieView.java` | ✅ Yes | Core renderer — draws X11 framebuffer on Android Surface |
| `InputEventSender.java` | ✅ Yes | Touch → pointer, keyboard → keycodes |
| `renderer.cpp` | ✅ Yes | EGL/GLES native rendering |
| `input.cpp` | ✅ Yes | Native input event processing |
| `compositor.cpp` | ❌ No | This is the X server itself — runs as a separate process |
| `MainActivity.java` | ❌ No | We have our own Activity |
| `Prefs.java` | ❌ No | We have our own settings |

### Integration Steps

1. **Fork the native rendering code** (`renderer.cpp`, `input.cpp`)
   - Compile as a shared library: `libxiaoian-x11.so`
   - Expose JNI functions: `nativeInit`, `nativeSurfaceChanged`, `nativeRender`, `nativeTouch`, `nativeKey`

2. **Create `X11SurfaceView`** (Kotlin)
   ```kotlin
   class X11SurfaceView(context: Context) : SurfaceView(context),
       SurfaceHolder.Callback {

       init {
           System.loadLibrary("xiaoian-x11")
           holder.addCallback(this)
       }

       override fun surfaceCreated(holder: SurfaceHolder) {
           nativeInit(holder.surface)
       }

       override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
           nativeSurfaceChanged(w, h)
       }

       override fun onTouchEvent(event: MotionEvent): Boolean {
           nativeTouch(event.action, event.x, event.y)
           return true
       }

       // JNI
       private external fun nativeInit(surface: Surface)
       private external fun nativeSurfaceChanged(w: Int, h: Int)
       private external fun nativeRender()
       private external fun nativeTouch(action: Int, x: Float, y: Float)
       private external fun nativeKey(keyCode: Int, down: Boolean)
   }
   ```

3. **Connect to the X server socket**
   - The `termux-x11` server process (running in `$PREFIX/bin`) creates `/tmp/.X11-unix/X0`
   - The renderer connects to this socket to receive framebuffer updates
   - Same mechanism as the standalone Termux:X11 app

---

## Wayland/Anland Renderer (Anland.Termux fork)

### Source Analysis Required

The Anland.Termux app's architecture (based on script analysis):

```
Anland.Termux app (com.anland.termux)
├── Android frontend:
│   ├── MainActivity           ← Activity setup
│   ├── Display rendering      ← SurfaceView that shows the Wayland output
│   ├── Input handling         ← Touch/keyboard → Wayland input events
│   └── Audio forwarding       ← Speaker + mic bridge to Android audio
└── Native code:
    └── Wayland client         ← Connects to display_daemon.sock
```

> **Action required:** Inspect the `lfdevs/anland-termux` GitHub repository to confirm:
> 1. Is the Android app source code available?
> 2. What license does it use?
> 3. Is there native (C/C++) code for rendering?

### Integration Approach (assuming open source)

1. **Fork the rendering View** (equivalent to LorieView for X11)
   - The View that connects to `display_daemon.sock` and renders Wayland frames

2. **Fork the audio bridge**
   - Anland forwards speaker output and mic input between the chroot's PipeWire and Android's AudioTrack/AudioRecord
   - This must be preserved in the Xiaoian app

3. **Create `AnlandSurfaceView`** (Kotlin)
   ```kotlin
   class AnlandSurfaceView(context: Context) : SurfaceView(context) {

       fun connectToSocket(socketPath: String) {
           // Connect to anland display_daemon.sock
           // Start receiving frames
           // Start audio forwarding
       }

       override fun onTouchEvent(event: MotionEvent): Boolean {
           // Forward to Wayland compositor via socket
           return true
       }
   }
   ```

### Fallback: If Anland source is not available

If the Anland.Termux app is closed source:

| Option | Effort | Result |
|---|---|---|
| **A: Keep Anland as separate app** | 0 | User installs 2 apps for KDE (Xiaoian + Anland) |
| **B: Negotiate with lfdevs** | Variable | Licensing agreement for rendering code |
| **C: Write own Wayland renderer** | 4–6 weeks | Custom Wayland client using `libwayland-client` via NDK |

Option C is technically feasible but significant work. The Local Desktop project did something similar (custom Wayland compositor in NDK).

---

## Input Handling

### Touch → Pointer

```
Touch event on phone/external touchscreen
    │
    ├─ ACTION_DOWN  → pointer button press + position
    ├─ ACTION_MOVE  → pointer motion
    ├─ ACTION_UP    → pointer button release
    │
    ├─ Multi-touch:
    │   ├─ 2 fingers → right-click (configurable)
    │   ├─ 3 fingers → middle-click (configurable)
    │   └─ Pinch    → scroll (configurable)
    │
    └─ Coordinates scaled to desktop resolution
```

### Keyboard

```
Android KeyEvent
    │
    ├─ Hardware keyboard:
    │   └─ Direct keycode mapping (Android → X11/Wayland keycode)
    │
    ├─ Soft keyboard:
    │   ├─ Character input → XKeysymToKeycode
    │   └─ Special keys → mapped via lookup table
    │
    └─ Extra keys bar:
        └─ Ctrl, Alt, Tab, Esc → injected as modifier events
```

### Mouse (USB/Bluetooth)

When an external mouse is connected:
- Relative motion mode (the pointer moves on the desktop, not on Android)
- Scroll wheel → scroll events
- Extra buttons → configurable mapping

---

## Display Lifecycle

### External Display Events

```kotlin
class DisplayDetector(context: Context) {

    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun findExternalDisplay(): Display? {
        return displayManager.displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
            display.flags and Display.FLAG_PRESENTATION != 0
        }
    }

    fun registerCallback(callback: (connected: Boolean, displayId: Int) -> Unit) {
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                callback(true, displayId)
            }
            override fun onDisplayRemoved(displayId: Int) {
                callback(false, displayId)
            }
            override fun onDisplayChanged(displayId: Int) { /* resolution change */ }
        }, null)
    }
}
```

### Hot-plug Handling

| Event | Action |
|---|---|
| Display connected (session not running) | Show "External display detected" in UI, suggest starting |
| Display connected (session running in local mode) | Offer to switch to extend mode |
| Display disconnected (session running in extend mode) | Bring DisplayActivity back to phone, or offer to switch to local |
| Display disconnected (session running in mirror mode) | Reset wm size/density, switch to local |

---

## Estimated Effort

| Component | Time |
|---|---|
| X11 renderer fork + integration | 5–7 days |
| Anland renderer fork + integration (if source available) | 5–7 days |
| DisplayActivity with mode switching | 2–3 days |
| Input handling (touch, keyboard, mouse) | 3–4 days |
| Display hot-plug handling | 2–3 days |
| NDK build setup (CMakeLists.txt) | 1–2 days |
| Testing on external displays | 3–4 days |
| **Total** | **~3–4 weeks** |
