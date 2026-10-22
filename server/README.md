# StreamHub Server

Runs on a computer or NAS at home and downloads movies for StreamHub, so downloads continue when
the TV is off. StreamHub shows these downloads in **My Downloads** like any other, and plays the
finished files from the server. Other players (VLC…) can play them too.

Supported: Windows (64-bit), Linux (Intel/AMD 64-bit, 32-bit ARM such as Raspberry Pi OS 32-bit),
macOS, and NAS boxes that run Docker on an Intel/AMD processor.

## 1. Install

1. Install **Java 17 or newer** (free: https://adoptium.net → "Temurin 17 JRE").
2. Download `streamhub-server.zip` from the latest StreamHub release and unzip it, e.g. to
   `C:\StreamHub Server` or `/opt/streamhub-server`.
3. Optional, for resolution / HDR / audio details in StreamHub: install **FFmpeg** (it brings `ffprobe`).

## 2. Start

* **Windows:** double-click `bin\streamhub-server.bat`. To start it with Windows, press Win+R, type
  `shell:startup`, and put a shortcut to `streamhub-server.bat` in that folder.
* **Linux:** run `bin/streamhub-server`. To start it with the computer, see `streamhub-server.service`.
* **NAS (Docker):** see `Dockerfile`.

The window shows a **pairing code** (also saved in `pairing-code.txt` in the data folder,
`~/.streamhub-server` or `C:\Users\<you>\.streamhub-server`).

## 3. Connect StreamHub

On the TV: **Settings → Downloads → Home Server → Configure server**. Enter the computer's address
(e.g. `192.168.1.100`; port 8095) and the pairing code. Then choose **Home Server** as the download
location. A code works once; the server shows a new one for the next device.

## Where downloads go

By default: `StreamHub Downloads` in your home folder. To use a drive, stop the server and run once:

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
  `smb://192.168.1.100/Movies`) as `smbPath` in `config.json` to show it in StreamHub.

## Security

Only paired devices can use the server (each gets its own key). It is meant for your home network:
**don't forward its port (8095) on your router**. To unpair a device, delete it from `devices` in
`config.json` and restart the server.
