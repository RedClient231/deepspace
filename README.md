# DeepSpace — Android Virtual Space Engine

A production-grade Android Virtual Space (沙箱) app that creates an isolated, virtualized Android environment. Designed to run apps like GameGuardian with full memory-editing capabilities on **non-rooted** Android 13 devices.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   Host App                       │
│  (com.fast.utils.helper)                         │
│  ┌───────────┐  ┌───────────┐  ┌──────────────┐ │
│  │ Launcher   │  │ Installer │  │ DaemonService│ │
│  │  Activity  │  │ Activity  │  │  (IPC host)  │ │
│  └───────────┘  └───────────┘  └──────┬───────┘ │
│                                       │         │
│  ┌────────────────────────────────────┴───────┐ │
│  │            VirtualCore Engine               │ │
│  │  ┌──────────┐ ┌──────────┐ ┌────────────┐  │ │
│  │  │ VPackage │ │ Binder   │ │   Memory   │  │ │
│  │  │ Manager  │ │  Hooks   │ │  Bridge    │  │ │
│  │  └──────────┘ └──────────┘ └────────────┘  │ │
│  └────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
    ┌──────────┐  ┌──────────┐  ┌──────────┐
    │ Stub :p0 │  │ Stub :p1 │  │ Stub :pN │
    │ (GG app) │  │ (Game)   │  │ (Other)  │
    └──────────┘  └──────────┘  └──────────┘
```

## Key Features

- **No root required** — Uses `process_vm_readv/writev` for memory operations
- **Shared UID** — All processes run under the same Linux UID via `android:sharedUserId`
- **PLT Hooking** — Intercepts libc calls for file/IO redirection
- **Binder IPC Hooks** — Virtual package manager, activity manager
- **Memory Bridge** — IPC daemon handles GameGuardian's memory read/write
- **Fake `su`** — Intercepts root requests and returns success
- **Anti-Detection** — Hides virtual environment from apps
- **20 Stub Processes** — Run multiple cloned apps simultaneously

## Modules

| Module | Description |
|--------|-------------|
| `app` | Host application — launcher UI, permissions, daemon service |
| `engine` | Virtual engine core — hooks, IPC, package management |
| `native` | C++ code — PLT hooks, IO redirect, memory bridge, fake su |
| `stub` | Stub APK — 10 pre-defined processes for cloned apps |

## Build

### Prerequisites

- Android Studio Hedgehog+ or Gradle 8.5
- JDK 17
- Android SDK 33
- NDK r25+

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean
./gradlew clean
```

### CI/CD

Push to `main` triggers GitHub Actions build. Artifacts are uploaded automatically.

## How It Works

### 1. Installation
User selects an APK → parsed and copied to `/data/data/com.fast.utils.helper/files/virtual_space/apks/` → registered in VPackageManager.

### 2. Launch
Host sends intent to stub activity with target info → StubApp loads target APK via DexClassLoader → Binder hooks intercept system service calls.

### 3. Memory Access
GameGuardian calls `ptrace()` → intercepted by PLT hook → redirected to memory bridge → daemon performs `process_vm_readv/writev` → returns data.

### 4. Root Simulation
GameGuardian executes `su` → intercepted by hook → fake su returns success → memory operations handled by bridge.

## Target Device

- Android 13 (API 33)
- Helio G99 or similar
- Non-rooted
- Internal storage only

## Security Notes

- All virtual processes share the same UID (no privilege escalation)
- Memory bridge only operates on registered PIDs
- No real root access is granted
- Host package name is obfuscated (`com.fast.utils.helper`)

## License

Private — All rights reserved.
