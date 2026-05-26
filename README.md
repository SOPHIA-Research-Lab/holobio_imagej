# HoloBio ImageJ / Fiji Plugin

Digital holographic microscopy (DHM) post processing plugin for Fiji
## Requirements

- [Fiji](https://fiji.sc/) (ImageJ with Java 8)
- To **build from source**: the JDK inside your Fiji install (`Fiji.app/java/.../bin/javac`)

## Install

### Option A — use the pre-built JAR (easiest)

1. Download or build `HoloBio_.jar`.
2. Copy it into your Fiji plugins folder:
   - **Windows:** `Fiji.app\plugins\HoloBio_.jar`
   - **macOS:** `Fiji.app/plugins/HoloBio_.jar`
   - **Linux:** `Fiji.app/plugins/HoloBio_.jar`
3. Restart Fiji.

### Option B — build from source (Windows)

1. Open `build.ps1` and set `$FijiDir` to your Fiji folder (the folder that contains `Fiji.app` or is `Fiji.app` itself).
2. Run:
   ```powershell
   .\build.ps1
   ```
   Or double-click `build.bat`.
3. The script creates `HoloBio_.jar` and copies it into `Fiji.app\plugins\`. Restart Fiji.

## Use

1. Start Fiji.
2. Open your hologram image (**File → Open**).
3. Go to **Plugins → HoloBio → HoloBio DHM**.
4. Click **Use Active Image** to load the open image.
5. Pick a module (Phase Compensation, Phase Shifting, or Numerical Propagation), set wavelength and pixel pitch (µm), then run the pipeline buttons in the window.
6. Optional: **Tools → Speckle** for speckle filtering on amplitude or phase.

Outputs (Fourier transform, phase, amplitude) can be saved from the plugin’s save controls after you run a step.

## Project layout

| Path | Purpose |
|------|---------|
| `src/` | Java source and `plugins.config` |
| `build.ps1` / `build.bat` | Compile and install the plugin |
| `tools/` | Optional dev scripts (parity checks) |


 Nancy Burgos 2026
