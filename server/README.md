# StreamHub Server

Runs on a computer or NAS at home and downloads movies for StreamHub, so downloads continue when
the TV is off. StreamHub shows these downloads in **My Downloads** like any other, and plays the
finished files from the server. Other players (VLC…) can play them too.

Supported: Windows (64-bit), Linux (Intel/AMD 64-bit, 32-bit ARM such as Raspberry Pi OS 32-bit),
macOS, and NAS boxes that run Docker on an Intel/AMD processor.

## Easiest: Windows PC

1. Download **`streamhub-server-windows.exe`** from the latest StreamHub release:
   https://github.com/sreepv43-lab/Test/releases/latest/download/streamhub-server-windows.exe
2. Run it and follow the steps (no administrator needed; Java comes inside). If Windows SmartScreen
   says "Windows protected your PC", choose **More info → Run anyway** (the installer isn't signed
   with a paid certificate).
3. Open **StreamHub Server** from the Start menu or the desktop. Its control page opens in its own
   window (see below; closing it keeps the server running). When Windows asks whether StreamHub Server may use the network, choose **Allow**
   (private networks), otherwise the TV can't reach it.

It starts with Windows from then on (switch that off on the control page). To update, run the newer
installer. To uninstall: Settings → Apps → StreamHub Server (downloads and settings stay).

## Easiest: Linux PC (Ubuntu, Linux Mint, Debian, Pop!_OS…)

1. Download **`streamhub-server-linux.deb`** from the latest StreamHub release:
   https://github.com/sreepv43-lab/Test/releases/latest/download/streamhub-server-linux.deb
2. Double-click it and press **Install** (or in a terminal: `sudo apt install ./streamhub-server-linux.deb`).
   Java comes inside; nothing else is needed. For Intel/AMD 64-bit PCs.
3. Open **StreamHub Server** from the app menu. Its **control page** opens in its own window
   (also at http://localhost:8095 in a browser on that computer). Closing the window keeps the server
   running; open it again from the menu. It shows:
   * the address and **pairing code** to type on the TV,
   * where downloads are stored: **+ Add a drive or folder** to pick your USB drive,
   * the downloads with their progress, pause / resume / remove,
   * paired TVs, the server name, and **Start with this computer** (on from the first start).

To update, install the newer `.deb` the same way. To uninstall: `sudo apt remove streamhub-server`
(your downloads and settings stay).

## Other computers: the zip

1. Install **Java 17 or newer** (free: https://adoptium.net → "Temurin 17 JRE").
2. Download `streamhub-server.zip` from the latest StreamHub release and unzip it, e.g. to
   `C:\StreamHub Server` or `/opt/streamhub-server`.
3. Start it:
   * **Windows (without the installer):** double-click `bin\streamhub-server.bat`. To start it with Windows, press Win+R, type
     `shell:startup`, and put a shortcut to `streamhub-server.bat` in that folder.
   * **macOS / Linux:** run `bin/streamhub-server --open-browser`. To start it with the computer, see `streamhub-server.service`.
   * **NAS (Docker):** see `Dockerfile`.
4. Open **http://localhost:8095** on that computer for the control page. The pairing code is also in
   the window and in `pairing-code.txt` in the data folder (`~/.streamhub-server` or
   `C:\Users\<you>\.streamhub-server`).

Optional, for resolution / HDR / audio details in StreamHub: install **FFmpeg** (it brings `ffprobe`).

The control page opens only on the server computer itself. For a NAS without a screen, set
`"adminFromNetwork": true` in `config.json` (then any device on your home network can open
`http://<nas address>:8095`).

## Connect StreamHub

On the TV: **Settings → Downloads → Home Server**. Enter the computer's address (shown on the
control page, e.g. `192.168.1.100`) and the pairing code, press **Connect**, then **Use it for
downloads**. A code works once; the server shows a new one for the next device.

## Where downloads go

By default: `StreamHub Downloads` in your home folder. Add drives on the control page
(**+ Add a drive or folder**), or stop the server and run once:

    streamhub-server --add-storage="Elements HDD=E:\Movies"          (Windows)
    streamhub-server --add-storage="Elements HDD=/mnt/elements/Movies" (Linux)

Several storages can be added; their names appear in StreamHub. The list is in `config.json` in the
data folder (`defaultStorage` = where new downloads go).

**Safety:** StreamHub marks each storage folder with a small `.streamhub-storage` file. If a drive is
unplugged or not mounted, downloads to it stop with "storage unavailable" and continue when it is
back; nothing is written to the computer's own disk instead.

## Playing from other devices (VLC etc.)

* **HTTP:** StreamHub plays finished files over HTTP from `http://<server>:8095/media/<id>`.
* **SMB (VLC, Kodi, other TVs):** share the downloads folder with the operating system:
  * Windows: right-click the folder → Properties → Sharing → Share (pick your user, so a password is required).
  * Linux: add a share to Samba (`/etc/samba/smb.conf`) with `valid users = <you>`, then `sudo smbpasswd -a <you>`.
  * NAS: create a shared folder for it in the NAS settings.

  In VLC: Browse → Local network → the computer → the share. Put the address (e.g.
  `smb://192.168.1.100/Movies`) under **Other players** on the control page to show it in StreamHub.

## Security

Only paired devices can use the server (each gets its own key). It is meant for your home network:
**don't forward its port (8095) on your router**. To unpair a device, delete it from `devices` in
`config.json` and restart the server.
