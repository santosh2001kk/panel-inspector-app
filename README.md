# Panel Inspector — Android App

An Android app for electricians to scan electrical panels, detect circuit breakers using AI, draw work zones, and generate safety reports.

---

## How It Works

```
1. Login
       │
       ▼
2. Home Screen
   ├── Project Details  (fill site name, inspector, project)
   ├── AI Scan          (go to camera)
   └── Saved Reports    (view past PDF reports)
       │
       ▼
3. Camera (ScanActivity)
       │  Capture photo of electrical panel
       ▼
4. Work Zone (WorkZoneActivity)
       │  Draw green rectangle over the area you will work on
       │  App auto-generates red dashed Safety Buffer (8% expanded)
       │
       ├── "Identify Panel" → sends image to server (identifyOnly=true)
       │         Returns panel type + 1-line summary (no zone needed)
       │
       └── "Analyze Zone"  → sends image + zones to server
                 Returns breakers + bounding boxes + safety warnings
       │
       ▼
5. Result Screen (ResultActivity)
       │  Shows:
       │  ├── Panel type card (Prisma G / Prisma P / Okken)
       │  ├── Marked image (photo + zone overlays + bounding boxes)
       │  ├── Notes from AI
       │  └── Safety warnings
       │
       └── "Save Report" → generates PDF
       │
       ▼
6. Saved Reports (ReportsActivity)
       └── List of PDFs — Share or Delete
```

---

## App Screens

| Screen | Purpose |
|--------|---------|
| LoginActivity | User authentication |
| MainActivity | Home screen — project details, scan, reports |
| ProjectDetailsActivity | Enter project name, site, inspector name |
| ScanActivity | Camera capture |
| WorkZoneActivity | Draw work zone + safety buffer |
| ResultActivity | Show AI results, bounding boxes, warnings |
| ReportsActivity | List saved PDF reports |
| PhotoGuideActivity | Guide on how to take a good photo |
| PanelRegisterActivity | Register a panel asset |
| LocateVbbActivity | Locate the VBB (busbar) in the panel |
| DocumentsActivity | View project documents |

---

## Zone System

- **Work Zone** — green filled rectangle, drawn by the user in WorkZoneActivity
- **Safety Buffer** — red dashed rectangle, auto-generated at 8% expansion from work zone
- Coordinates stored as **0–1000 normalized** (xmin, ymin, xmax, ymax)
- Sent to server and used for bounding box filtering

---

## Connecting to the Server

In [GoogleStudioDetector.kt](app/src/main/java/com/example/testerapigoogle/GoogleStudioDetector.kt), update the IP to match your Mac's local IP:

```kotlin
const val BASE_URL = "http://<your-mac-ip>:8000/api/analyze"
```

To find your Mac's IP:
```bash
ipconfig getifaddr en0
```

Then rebuild the app in Android Studio: **Cmd+F9 → Run**

---

## PDF Report Structure

Each saved report contains:
1. Header bar with date
2. Project details (Project Name / Site / Inspector)
3. Marked image (photo with zone rectangles drawn on it)
4. Panel type
5. AI notes
6. Safety warnings

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | XML layouts + Material Design 3 |
| Camera | Android CameraX / Intent |
| Networking | OkHttp |
| PDF generation | Android Canvas (on-device, no library) |
| AI backend | FastAPI server + Google Gemini |

---

## Setup

1. Clone the repo and open in **Android Studio**
2. Update the server IP in `GoogleStudioDetector.kt`
3. Make sure the [Panel Inspector Server](https://github.com/santosh2001kk/panel-inspector-server-) is running on your Mac
4. Build and run on your Android device (**must be on the same WiFi network as the Mac**)
