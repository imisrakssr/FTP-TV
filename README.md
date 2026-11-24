# FTP TV 📺

Lightweight live-TV app for Android TV (built for the **Xiaomi Mi TV Stick**, armv7, 1 GB RAM) and phones,
modeled on DhakaFlix TV. It merges the BDIX live-TV servers into one clean channel list and plays the fastest one.
**Made ❤️ by Israk Sisir**

## Remote control
| Key | What it does |
|---|---|
| ▲ / ▼ (and CH+/CH−) | Next / previous channel, instantly (inside the list you picked last) |
| ◀ / ▶ | Volume down / up · **hold ◀ = mute** |
| OK | See-through overlay: categories + channel list (previous/next around the current one) + quick actions |
| Hold OK | Add/remove the channel in a favorites group |
| 0–9 | Jump to a channel number |
| Back ×2 | Exit |

Phone: tap = overlay, swipe up/down = channel, swipe left/right = volume, long-press = favorites. Floating pill tab bar, no status bar.

## Channels
Allow-list in `app/src/main/java/com/israk/ftptv/data/Catalog.kt` (order = channel numbers):
Bangladesh (almost all) · Kolkata Bangla · Hindi · Pakistan · English movies · Music · **BD-only news** ·
Sports (cricket/football + Eurosport, beIN, SSC) · Kids · Discovery/Nat Geo · Islamic. No food channels, no foreign news, no TLC/Travel XP.
Anything a server carries that isn't in the catalog is never shown.

## Servers (Settings → Servers, each can be turned off)
| Server | How it's read |
|---|---|
| 10.99.99.99 | `/api/channels` JSON, signed URLs (refreshed every ~80 min) |
| 172.16.60.2 | `play.php?stream=N` → Flussonic embed → `index.m3u8?token=…` |
| 172.20.21.22 (Funtime TV-1/2/3) | video.js playlists in the page / `channels.php` POST |
| tv.ksbnet.net:8099 (Appolo) | `/channels/list` + CSRF `POST /stream/token/{slug}` |
| redforce.live | `player.php?stream=N` (was under maintenance, Nov 2026) |
| Famelack | public free-to-air streams (BD / IN / PK / SA) — fills Pakistan & Makkah/Madinah |
| + your own | any M3U/M3U8 link |

When a channel is on several servers, the app races the best three, plays the first that answers, and falls over to the
next on errors or a 12-second stall. OK → **⇄ Server** lets you pin one by hand (only shown when there are backups).

## Build (GitHub Actions)
Every push to `main` builds a release: `FTPTV-v…-armv7.apk` → Mi TV Stick, `-arm64.apk` → phones.
Add a `Target: tv` or `Target: phone` line in the commit message to build only one.
In-app updates read this repo's Releases, so they work only while the repo is **public**.

Local: `bash ci/prepare.sh`, then open in Android Studio.

Tech: Kotlin · Android Views + RecyclerView (no Compose — lighter on 1 GB sticks) · Media3 ExoPlayer 1.9 (HLS, OkHttp) + FFmpeg audio · Coil · OkHttp.
