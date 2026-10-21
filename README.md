# HoloBio — Fiji Plugin

Digital holographic microscopy (DHM) and digital lensless holographic microscopy (DLHM) plugin
for Fiji, with offline post-processing and live reconstruction from a camera or video.

Ported from [HoloBio Python](https://sophia-research-lab.github.io/HoloBio/). The
reconstruction methods follow the Python reference implementations; the real-time DHM
compensation also follows the EAFIT MATLAB live tool https://github.com/SOPHIA-Research-Lab/RealTimeDHM_compensation.

Videos: How to use it
-	HoloBio Fiji Demo - GUI Overview and RT DHM module demo using holograms recorded at EAFIT university:
o	Link: https://www.youtube.com/watch?v=6EUapBbpczM

-	HoloBio Fiji Demo – Offline Module Overview
o	Link: https://youtu.be/weWtc1r98w4

## Modules

The plugin installs four commands under **Plugins ▸ HoloBio**:

| Command | What it does |
|---|---|
| **Offline DHM** | Off-axis DHM on an image open in Fiji: phase compensation (semi-heuristic / Tu-DHM / Vortex–Legendre), phase shifting (BPS2, BPS3, AIA), numerical propagation (angular spectrum, Fresnel) with z-scan and autofocus |
| **Offline DLHM** | Lensless in-line reconstruction (angular spectrum, Kreuzer, angular spectrum) with an optional reference hologram |
| **Real-Time DHM** | Live off-axis reconstruction with Vortex / Vortex–Legendre compensation, automatic or manual (+1 order) filtering, live phase profiles with CSV recording, histogram, video and complex-field recording, and live camera exposure / gain |
| **Real-Time DLHM** | Live lensless reconstruction (realistic angular spectrum) with linked L / z / r distances, histogram, video and complex-field recording, and live camera exposure / gain |

The **QPI** and **Speckle** analysis tools open from the offline modules' *Analysis tools* menu.

## Requirements

- [Fiji](https://fiji.sc/) with its bundled Java 8
- [FFmpeg](https://ffmpeg.org/) on your `PATH`, for video input and MP4 / AVI recording
  (still images, TIFF stacks and TIFF recordings work without it)
- A camera that Windows / Linux exposes as a standard video device (e.g. USB
  webcams, The Imaging Source DMK cameras, etc) for live acquisition and its driver installed.
- If an older install left `webcam-capture-0.3.12.jar` or `bridj-0.7.0.jar` in
   `Fiji.app/jars/`, delete them.

# Installation

## 1. Install FFmpeg

If FFmpeg is already installed and available in your system `PATH`, skip this step.

### Windows

Open **PowerShell** and run:

   ```powershell
   winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
   ```
   ```powershell
   $bin = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages", "$env:ProgramFiles\WinGet\Packages" -Directory -Filter "Gyan.FFmpeg*" -ErrorAction SilentlyContinue |
       Get-ChildItem -Recurse -Filter ffmpeg.exe |
       Select-Object -First 1 -ExpandProperty DirectoryName

   $p = [Environment]::GetEnvironmentVariable("Path", "User")

   if ($bin -and ($p -split ";") -notcontains $bin) {
       [Environment]::SetEnvironmentVariable("Path", "$p;$bin", "User")
   }

   $env:Path += ";$bin"
   ```
Verify the installation:
   ```powershell
   ffmpeg -version
   ```

### Linux (Ubuntu/Debian)

Open a terminal and run:
```bash
sudo apt-get update
sudo apt-get install -y ffmpeg unzip curl
```
Verify the installation:
```bash
ffmpeg -version
```

## 2. Install Fiji

If Fiji is already installed, skip this step.

### Windows
https://imagej.net/software/fiji/downloads

#### Linux
Download the latest Fiji distribution with Java included:
   ```bash
   curl -fL -o ~/fiji-latest-linux64-jdk.zip \
       https://downloads.imagej.net/fiji/latest/fiji-latest-linux64-jdk.zip
   ```
Extract the archive into your home directory:
   ```bash
   unzip -q -o ~/fiji-latest-linux64-jdk.zip -d ~/
   ```

   This should create the `~/Fiji.app/` directory.

## 3. Install the HoloBio plugin

#### Windows

1. Locate your `Fiji.app` installation directory.
2. Download and copy `HoloBio_.jar` into:
   ```text
   Fiji.app/plugins/
   ```
   
#### Linux

If Fiji is installed in `~/Fiji.app/`, run:
```bash
curl -fL -o ~/Fiji.app/plugins/HoloBio_.jar \
    https://raw.githubusercontent.com/SOPHIA-Research-Lab/holobio_imagej/main/HoloBio_.jar
```

## 4. Restart Fiji.

Note: The Offline modules and the Video options work in Linux. Live acquisition in Linux is still experimental and might need extra driver set up and configuration depending of your camera.

## Use

### Offline

1. **File ▸ Open** your hologram (or a stack for phase shifting).
2. **Plugins ▸ HoloBio ▸ Offline DHM** (or **Offline DLHM**).
3. **Use Active Image** to link the open image.
4. Choose a submodule, set wavelength and pixel pitch in µm, and run the pipeline.
5. Use analysis tools.
   
### Real-time

1. **Plugins ▸ HoloBio ▸ Real-Time DHM** (or **Real-Time DLHM**).
2. In **Capture**, choose *Camera* (then **Refresh** and a **Resolution**; the largest is
   selected by default) or *Video* (then **Browse…** for a video, still image or TIFF stack).
3. Set **Optics** and press **Start**. Fields can be edited while running; each change
   applies on the next frame and is confirmed in the status line.
4. With a camera running on Windows, a **Camera** section shows **Exposure** (with *Auto*)
   and **Gain**. It reads the camera's current values and changes nothing until you move a
   control. Settings are stored by the camera driver, not by HoloBio. ONLY WORKS IN WINDOWS!!!
5. **Histogram** (under Optics) opens a live intensity histogram of the raw hologram, with
   its mean, SD, min and max, to help set exposure.
6. **Record**:
   - **Start recording** / **Stop and save…** records the hologram, amplitude or phase as
     MP4, AVI or TIFF stack (up to 512 MB in memory). Holograms are always saved
     **lossless**, so they can be reconstructed again later.
   - **Record complex fields** / **Stop and save fields…** streams every reconstructed field
     to a NumPy `.npz` (`frame_00000`, `frame_00001`, … as `complex64`). Files grow quickly;
     frames are skipped (and counted) if the disk can't keep up.
7. **Snap to Fiji** sends both current views to Fiji. Repeated snaps become time-stamped
   slices of one stack per view.
8. *(Real-Time DHM)* **Profile ▸ Live** draws lines or regions on the phase and plots their
   phase profiles every frame; **Record…** / **Save…** writes Δφ per frame to CSV, and
   **Load ROIs…** reads `rois_*.txt` files that have two x,y coords and draws a line between them. Used for validation.

## Project layout

| Path | Purpose |
|---|---|
| `src/` | Java sources and `plugins.config` (menu definition) |
| `lib/` | Third-party JARs bundled at build time, and their notices |
| `build.ps1` / `build.bat` | Compile, self-check and install into Fiji |
| `HoloBio_.jar` | Pre-built plugin |


| Algorithm | File |
|---|---|
| Phase compensation ( Semi-Heuristic, Tu-DHM) and spectrum filtering | `HoloBioCompensationAlgorithms.java` |
| Vortex-Legendre (offline) | `HoloBioVortexLegendre.java` |
| Real-time DHM pipeline (+1-order detection, vortex carrier, reference wave) | `HoloBioRtDhmMath.java` |
| Real-time Legendre fit  | `HoloBioLegendreLive.java` |
| Phase unwrapping, weighted least squares (offline Vortex-Legendre) | `HoloBioWeightedPhaseUnwrap.java`, with `HoloBioDct2.java` for its transforms |
| Phase shifting (BPS2/3) | `HoloBioPhaseShifting.java` |
| (AIA) Iterative phase shifting | `HoloBioAiaMath.java` |
| Numerical propagation for DHM (angular spectrum, Fresnel) | `HoloBioPropagationMath.java` |
| DLHM reconstruction (angular spectrum, Kreuzer, R. angular spectrum / MAS) | `HoloBioDlhmMath.java` |
| Real-time DLHM wrapper (calls `HoloBioDlhmMath` with cached buffers) | `HoloBioRtDlhmMath.java` |
| QPI and speckle analysis | `HoloBioQpiSpeckleMath.java` |
| FFT | `HoloBioRectFft.java` |
| Multi-core splitting of row/column operations | `HoloBioParallel.java` |
| microstructure statistics (UNFINISHED NOT IN UI) | `HoloBioMicrostructureMath.java` |

## Third-party

Bundled into `HoloBio_.jar` 

- [webcam-capture](https://github.com/sarxos/webcam-capture) 0.3.12 — camera access (MIT)
- [BridJ](https://github.com/nativelibs4java/BridJ) 0.7.0 — native access for webcam-capture (BSD-3)
- [jcobyla](https://github.com/xypron/jcobyla) 1.4 — COBYLA optimiser for BPS2 (MIT)

Provided by Fiji: ImageJ, [JNA](https://github.com/java-native-access/jna) (DirectShow
exposure / gain / resolution), Jama (AIA).

External: [FFmpeg](https://ffmpeg.org/), run as a separate program for video.

---

### Extra: Adding your own code and fast compiling (Windows)

If you want to add your own functions and don't want to build and copy paste the .jar into Fiji everytime, you can use the build.bat or build.ps1 scripts for fast builts.

0. Add whatever code you want to .java files under /src
1. Open `build.ps1` and set `$FijiDir` to your Fiji folder, and `$Javac` / `$Jar` /
   `$IjJar` to match the JDK and `ij-*.jar` versions your Fiji ships.
2. Run:
   ```powershell
   .\build.ps1
   ```
   (or double-click `build.bat`)
3. The script compiles `src/`, bundles jcobyla, webcam-capture and BridJ, runs a
   self-check, then installs `HoloBio_.jar` into `Fiji.app\plugins\` and
   `HoloBio-core.jar` into `Fiji.app\jars\` (removing old separate webcam/BridJ JARs there). It will print every step and tell you if it was successful
4. Restart Fiji.

Nancy Burgos, 2026. Contact me! ncburgosb@eafit.edu.co
