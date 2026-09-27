# MeshTalk

**Chat without internet, SIM cards or Wi-Fi.**
MeshTalk lets small ESP32 gadgets (with a keypad and a little screen) and Android phones send messages to each other over Bluetooth. Messages hop from device to device, so they can travel much further than one Bluetooth link.

---

## Contents

1. [What is MeshTalk?](#1-what-is-meshtalk)
2. [How it works in one minute](#2-how-it-works-in-one-minute)
3. [What's in this folder](#3-whats-in-this-folder)
4. [What you need](#4-what-you-need)
5. [Build the ESP32 node](#5-build-the-esp32-node)
6. [Use the ESP32 node (keypad + screen)](#6-use-the-esp32-node-keypad--screen)
7. [Install and use the Android app](#7-install-and-use-the-android-app)
8. [Your first test (step by step)](#8-your-first-test-step-by-step)
9. [How it works (more detail)](#9-how-it-works-more-detail)
10. [Troubleshooting](#10-troubleshooting)
11. [Settings you can change](#11-settings-you-can-change)
12. [Rebuild the app yourself](#12-rebuild-the-app-yourself)
13. [Limits and safety](#13-limits-and-safety)
14. [Code map (for developers)](#14-code-map-for-developers)
15. [Glossary](#15-glossary)

---

## 1. What is MeshTalk?

Normal chat apps need a phone tower or Wi-Fi. When those are down (after a flood or earthquake, deep in the countryside, at a crowded festival), normal chat stops working.

MeshTalk doesn't need any of that. Every device running MeshTalk is a **node**, and every node does three jobs:

| Job | Meaning |
|---|---|
| **Send** | You can write messages. |
| **Receive** | You get messages meant for you. |
| **Relay** | You pass on other people's messages, even ones you can't read. |

Because everyone relays, the network grows as more devices join. Nobody is in charge. There is no server.

There are two kinds of node, and they work together:

- **ESP32 node:** a small board with a 4x4 keypad and a 16x2 LCD screen. You type the old Nokia way.
- **Android phone:** runs the MeshTalk app. It's a full node, just like an ESP32, with some extras (notifications, a network map, and an admin panel).

---

## 2. How it works in one minute

Think of a classroom where you can only whisper to the people sitting next to you.

```
  You (A) ──whisper──► B ──whisper──► C ──whisper──► Friend (D)
```

A wants to reach D at the back of the room. A whispers the note to B. B passes it to C. C passes it to D. D whispers back "got it!" the same way.

MeshTalk does exactly this over Bluetooth:

- Every note carries a **hop counter** (it starts at 7). Each device that passes it on subtracts 1. At 0 the note stops travelling, so notes can't go round in circles forever.
- Every device **remembers which notes it has already passed on** and ignores copies, so the room doesn't fill up with echoes.
- If D has left the room, the helpers **keep the note** and hand it over when D comes back (this is called *store-and-forward*).
- D sends back a **receipt** ("delivered ✓✓"). Until the sender gets it, the sender keeps trying.

---

## 3. What's in this folder

```
meshtalk/
├── README.md                  ← you are here
├── dist/
│   └── MeshTalk-1.0.0.apk     ← ready-to-install Android app
├── firmware/
│   └── meshtalk/
│       └── meshtalk.ino       ← the ESP32 program (one file)
└── android/                   ← the Android app's source code
    ├── app/src/main/java/com/meshtalk/   ← Kotlin code
    ├── app/src/test/                      ← automatic tests
    ├── meshtalk-release.jks               ← app signing key (keep safe!)
    └── keystore.properties                ← signing key passwords (keep safe!)
```

---

## 4. What you need

### For each ESP32 node

| Part | Notes |
|---|---|
| ESP32 dev board | The classic "ESP32 DevKit / WROOM-32" (it has pins 32 and 33). ESP32-S3/C3 boards are **not** supported as-is. |
| 4x4 matrix keypad | The common membrane keypad with keys 0–9, A–D, `*`, `#`. |
| 16x2 LCD with I2C backpack | The blue/green LCD with a small PCF8574 board soldered on the back. Address `0x27` (the most common). |
| Jumper wires | 8 for the keypad, 4 for the LCD. |
| Power | USB cable, or a USB power bank to carry it around. |

### For the phone

- Android **8.0 or newer**, with Bluetooth.
- About 5 MB of free space.

### For your computer (only to program the ESP32)

- **Arduino IDE 2** (free, from arduino.cc).
- A USB cable that carries data (some cheap cables only charge).

---

## 5. Build the ESP32 node

### 5.1 Wiring

**Keypad** (looking at the keypad's ribbon connector, the 8 wires go left → right):

| Keypad wire | 1 (row 1) | 2 (row 2) | 3 (row 3) | 4 (row 4) | 5 (col 1) | 6 (col 2) | 7 (col 3) | 8 (col 4) |
|---|---|---|---|---|---|---|---|---|
| **ESP32 pin** | GPIO 32 | GPIO 33 | GPIO 25 | GPIO 26 | GPIO 18 | GPIO 19 | GPIO 5 | GPIO 17 |

> If keys come out wrong (e.g. pressing `1` gives `D`), your keypad's wire order is reversed. Flip the 8 wires around. Nothing gets damaged.

**LCD (I2C backpack)**:

| LCD pin | ESP32 pin |
|---|---|
| GND | GND |
| VCC | 5V (called VIN on some boards) |
| SDA | GPIO 13 |
| SCL | GPIO 12 |

> ⚠️ **Important: the GPIO 12 problem.**
> GPIO 12 is a special pin that the ESP32 checks at the moment it powers on. The LCD board can pull this pin up, and that makes the ESP32 think it has a different type of memory chip, so **it won't start** (it keeps restarting, or the serial monitor shows garbage).
>
> **If your board won't start with the LCD connected**, pick one fix:
> 1. **Easy fix:** move the SCL wire from GPIO 12 to **GPIO 22**. Then open `meshtalk.ino`, find `#define I2C_SCL 12` near the top, change it to `#define I2C_SCL 22`, and upload again.
> 2. **Permanent fix (advanced):** run `espefuse.py set_flash_voltage 3.3V` once. This tells the chip to ignore GPIO 12 at start-up forever. It can't be undone.

**LCD shows only boxes, or nothing?** Turn the small blue screw (contrast) on the back of the LCD with a screwdriver until text appears.

### 5.2 Install the software (one time)

1. Install **Arduino IDE 2** from https://www.arduino.cc/en/software.
2. Add ESP32 support:
   - Open **File → Preferences**. In "Additional boards manager URLs" paste
     `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
   - Open **Tools → Board → Boards Manager**, search **esp32**, and install **"esp32 by Espressif Systems"** (version 3.x).
3. Add the two libraries: open **Tools → Manage Libraries**, then search and install:
   - **Keypad** (by Mark Stanley, Alexander Brevig)
   - **LiquidCrystal I2C** (by Frank de Brabander)

(On the PC this project was built on, all of these are already installed.)

### 5.3 Upload the program

1. Plug the ESP32 into your computer.
2. In Arduino IDE: **File → Open** → `firmware/meshtalk/meshtalk.ino`.
3. **Tools → Board → esp32 → ESP32 Dev Module.**
4. **Tools → Port →** pick the port that appeared when you plugged in (e.g. `COM5`).
5. Click **Upload** (the → arrow). Wait for "Done uploading".
   - If it's stuck at `Connecting.....`, hold the **BOOT** button on the board until uploading starts.

### 5.4 First start

The screen shows `MeshTalk / Starting...`, then the **home screen**:

```
┌────────────────┐
│ESP-3F2A        │   ← your node's name (you can change it)
│D:Menu  Nb0 N0  │   ← Nb = neighbours right next to you, N = all nodes seen
└────────────────┘
```

Every node gets a unique 4-character **ID** (like `3F2A`) based on its chip. It never changes.

> Tip: open **Tools → Serial Monitor** at **115200** baud to see start-up messages. This is helpful when something doesn't work.

Do the same for a second ESP32. Within about 20 seconds, both should show **`Nb1`**, which means they can hear each other.

---

## 6. Use the ESP32 node (keypad + screen)

### 6.1 Key cheat sheet

```
 ┌───┬───┬───┬───┐
 │ 1 │ 2 │ 3 │ A │    A = up / previous
 ├───┼───┼───┼───┤
 │ 4 │ 5 │ 6 │ B │    B = down / next
 ├───┼───┼───┼───┤
 │ 7 │ 8 │ 9 │ C │    C = back / cancel
 ├───┼───┼───┼───┤
 │ * │ 0 │ # │ D │    D = OK / open / send
 └───┴───┴───┴───┘
```

| Key | What it does |
|---|---|
| **D** | OK, open, select, send |
| **C** | Go back or cancel |
| **A / B** | Move up / down in lists. **Hold** to scroll fast. |
| **1–8** (on home or menu) | Jump straight to that menu item |
| **A** on home | Open Inbox |
| **B** on home | Open Nearby nodes |

**How a press counts:** hold each key for about **a quarter of a second** (0.25 s). Very quick bumps are ignored on purpose, so the node doesn't react when it's in your pocket.

**Screen light:** it turns off after 1 minute of no use. Your first key press only turns the light back on, it doesn't do anything else.

### 6.2 The little symbols on screen

| Symbol | Meaning |
|---|---|
| ✉ (envelope) | Unread message. On the home screen, the number next to it = how many unread. |
| 🕓 (clock) | Your message is still on its way (waiting for "delivered"). |
| ✓ (tick) | Your message was **delivered**. |
| ✗ | Your message could not be delivered (gave up after 1 hour). |
| 📡 (antenna) | A broadcast (sent to everyone). |
| 🔔 (bell) | An SOS alert. |
| 👤 (person) | A friend. |

### 6.3 Typing text (old Nokia style)

Each number key has letters on it. **Tap the same key again to move to the next letter.**

| Key | Letters (tap 1×, 2×, 3×, …) |
|---|---|
| 1 | `.` `,` `?` `!` `'` `-` `@` `:` `1` |
| 2 | a b c 2 |
| 3 | d e f 3 |
| 4 | g h i 4 |
| 5 | j k l 5 |
| 6 | m n o 6 |
| 7 | p q r s 7 |
| 8 | t u v 8 |
| 9 | w x y z 9 |
| 0 | space, 0 |

**Useful rules:**

- **Wait 1 second** (or press a different key) and the letter is locked in. A blinking block shows the letter you're still choosing.
- **Hold a number key for 1 second** to type the number itself (e.g. hold `5` → `5`).
- `*` = **delete** the last letter. **Hold** `*` to delete everything.
- `#` = change mode. The current mode shows in the top-right corner:
  - `Abc`: next letter is a capital, then back to small letters (this is the mode when you start).
  - `abc`: all small letters.
  - `ABC`: all capitals.
  - `123`: every key types its number directly.
- The number in the top-right corner shows how many characters you have left (max 160).
- **D** sends. **C** leaves (your text is kept as a draft if you come back to the same person).

**Example: typing `Hi 5`**

| Press | Screen shows | Why |
|---|---|---|
| `4` `4` | `H` | 4 twice = h, capital because mode starts as `Abc` |
| `4` `4` `4` | `Hi` | a different letter on the same key, so first wait 1 s after the `H` |
| `0` | `Hi ` | space |
| hold `5` | `Hi 5` | holding types the number |
| `D` | *sent!* | |

### 6.4 The menu

Press **D** on the home screen to open the menu. Use **A/B** to move and **D** to choose, or press the item's number.

| # | Item | What it's for |
|---|---|---|
| 1 | **Inbox** | All messages, newest first. Open one with D. In a message: A/B scrolls, **D replies**, C goes back. |
| 2 | **New message** | Pick a friend, type, press D to send. |
| 3 | **Broadcast** | Send a message to **everyone** in the network (no friend request needed). |
| 4 | **Friends** | Your friends list. Choose one to **Send message** or **Remove friend**. |
| 5 | **Nearby nodes** | Every node the network can reach. The right side shows signal strength (e.g. `-62`) if it's right next to you, or `2hop` if the message has to pass through others. **Choose a node to send it a friend request** (or to message it if it's already a friend). |
| 6 | **Requests** | Friend requests other people sent you. Choose one: **D = accept**, **`*` = decline**, C = decide later. |
| 7 | **SOS alert** | Emergency message to **everyone**. **Hold D** for 1 second to send (a quick tap does nothing, which prevents accidents). |
| 8 | **Settings** | **Change name** (up to 9 letters), **Node info** (ID, uptime, number of messages relayed, neighbours, pending messages), **Clear inbox**. |

### 6.5 Pop-ups

When something arrives, it pops up over whatever you're doing:

| Pop-up | Keys |
|---|---|
| **New message** | D = read it now, C = close (it stays in the Inbox) |
| **Friend request** ("Req: Rahim") | D = accept, `*` = decline, C = later (find it in menu 6) |
| **SOS** | The screen light **flashes** until you press any key. D = read it. |

### 6.6 Friends: why you need them

You can only send **private messages** to **friends**. This stops strangers from messaging you.

To become friends:

1. On node A: menu **5 Nearby nodes** → choose node B → the screen shows "Asking...".
2. On node B: a pop-up "Req: A" appears → press **D** to accept.
3. Both nodes now list each other in **4 Friends**.

Anyone can still read **broadcasts** and **SOS** alerts, and every node still relays everyone's messages. Friendship only controls who can start a private chat.

---

## 7. Install and use the Android app

### 7.1 Install

1. Copy `dist/MeshTalk-1.0.0.apk` to your phone (USB cable, Google Drive, WhatsApp to yourself, anything works).
2. Tap the file. If Android asks, allow **"Install unknown apps"** for the app you opened it with.
3. Tap **Install**, then **Open**.

### 7.2 First start

1. **Allow permissions.** The app asks for:
   - **Nearby devices / Bluetooth:** needed to talk to other nodes.
   - **Location:** Android lists Bluetooth scanning under "location". MeshTalk **never reads or shares your location**.
   - **Notifications:** so you see new messages and SOS alerts.
2. **Turn on Bluetooth** if it's off.
3. **Pick a name** (up to 9 characters so it fits on the ESP32 screens).

That's it. A notification saying **"MeshTalk node active"** appears. This means your phone is now part of the network and **keeps relaying even when the app is closed**.

> **Samsung / Xiaomi / Oppo / Vivo / Realme phones** like to kill background apps.
> Go to **Settings (⚙ in the app) → "Allow background running"** and allow it. Otherwise your phone may stop relaying when the screen turns off.

### 7.3 The four tabs

**💬 Chats**
- **Everyone** at the top is the broadcast channel. Anything you post there reaches all nodes.
- Below that, one row per friend, with a preview of the last message and a green dot if they're online.
- Tap a row to open the chat. Your messages show a status:
  - 🕓 **sending**: still on the way.
  - ✓ **sent**: a broadcast has gone out (broadcasts don't get receipts).
  - ✓✓ **delivered**: your friend's device received it.
  - ⚠ **not delivered**: gave up after 24 hours. **Tap the message to try again.**

**📍 Nearby**
- Every node the network knows about (phones and ESP32s).
- For each one: type, signal strength with an estimated distance (e.g. `-62 dBm · ~3 m`), or **"2 hops"** if it's further away, and when it was last seen.
- Button on the right:
  - **Add**: send a friend request.
  - **Requested**: waiting for them to answer.
  - **Accept**: they asked you, tap to accept.
  - **Message**: already friends, opens the chat.

**👤 Requests**
- **Incoming:** people who want to be your friend. **Accept** or **Decline**.
- **Sent:** requests you're waiting on. **Cancel** if you change your mind. Requests keep retrying automatically until the other person answers.

**🔒 Admin** (PIN protected, see [7.5](#75-admin-mode))

### 7.4 SOS (emergency)

1. Tap the red **SOS** button at the top of the screen.
2. Optionally type details (e.g. "injured, at north gate").
3. Tap **SEND SOS**.

What happens:
- The alert goes to **every** node (phones and ESP32s), not just friends.
- It travels **further** than normal messages (15 hops instead of 7) and **jumps the queue** on every device.
- Phones play an **alarm sound**, vibrate, and show a **red banner** until you tap it.
- ESP32 screens **flash** until someone presses a key.

### 7.5 Admin mode

Tap the **Admin** tab and enter the PIN. **The default PIN is `1234`, so change it** in Settings before real use. After 5 wrong tries the screen locks for 30 seconds.

At the top you'll see **counters**: active nodes, links, messages relayed by this phone, packets received/sent (RX/TX), pending messages, SOS alerts seen, and uptime.

Then four sub-tabs:

| Tab | What you see |
|---|---|
| **Graph** | A live **map of the network**. Dots = nodes (big outlined dot = you, teal = ESP32, blue = phone, red = sent an SOS in the last 10 min). Lines = direct Bluetooth links, coloured by strength: **green** strong, **yellow** fair, **red** weak. Each line is labelled with signal strength and an estimated distance. |
| **Nodes** | A list of every node: ID, type, hops away, signal, uptime, how many messages it has relayed, number of neighbours, last seen. |
| **Links** | Every connection between two nodes, with signal strength and estimated distance. |
| **Live** | A running log of every packet this phone sends (TX), receives (RX) and passes on (FWD). SOS lines are shown in red. |

> How does a phone know about links it isn't part of? Every node tells the network who its neighbours are every ~30 seconds. The admin phone collects those reports and draws the map.

### 7.6 Settings (⚙ icon)

- **Display name:** change your name.
- **Node ID:** your unique 4-character ID.
- **Admin PIN:** change it (enter the current PIN, then a new one with 4–8 digits).
- **Allow background running:** see the tip in [7.2](#72-first-start).
- **Stop mesh node and exit:** turns MeshTalk off completely (your phone stops relaying).

---

## 8. Your first test (step by step)

Do these in order. Each step tells you what you should see.

| # | Do this | You should see |
|---|---|---|
| 1 | Power two ESP32 nodes, a few metres apart. | Within ~20 s both home screens show **`Nb1`**. |
| 2 | Install and open the app on a phone. | **Nearby** tab lists both ESP32s with signal strength. |
| 3 | On the phone, tap **Add** next to an ESP32. | The ESP32 shows **"Req: <phone name>"**. Press **D**. The phone shows "…accepted your friend request" and the ESP32 appears in **Chats**. |
| 4 | Send a message from the phone to the ESP32, then reply from the ESP32 (Inbox → open → **D** to reply). | ESP32 pops up the message. Phone shows **✓✓ delivered**, then the reply arrives with a notification. |
| 5 | **Multi-hop test:** walk ESP32-A far away from the phone (different room or 30+ m) and put ESP32-B halfway. | In Nearby, A changes from signal strength to **"2 hops"**. Messages still arrive. Admin → Graph shows **A — B — phone**. |
| 6 | **Store-and-forward test:** unplug an ESP32, send it a message from the phone (shows 🕓), then plug it back in. | Within a few seconds after it starts up, the message arrives and the phone shows ✓✓. |
| 7 | **SOS test:** on an ESP32, menu **7**, **hold D**. | Every phone plays an alarm and shows a red banner. Every other ESP32 flashes. |

If a step fails, see [Troubleshooting](#10-troubleshooting).

---

## 9. How it works (more detail)

This section is for the curious, and for your project report.

### 9.1 Bluetooth "shouting" instead of connecting

Most Bluetooth gadgets **pair and connect** one-to-one. MeshTalk doesn't. Every node just keeps **shouting short packets into the air** (Bluetooth *advertisements*) and **listening** for other nodes' packets all the time.

Why this way:
- There's nothing to pair and no connection to drop.
- Any number of devices can listen to one shout at the same time.
- Phones and ESP32s use exactly the same method, so they're equal members.

Each packet is tiny: **24 bytes**. Each node shouts one packet at a time, for 0.25 s (ESP32) or 0.4 s (phone), repeating it several times in that window so listeners don't miss it.

### 9.2 What's inside a packet

```
byte:  0      1      2         3-4    5-6    7-8    9          10 ... 23
     ┌──────┬──────┬─────────┬──────┬──────┬──────┬──────────┬──────────────┐
     │ 'M'  │ type │hops|TTL │ from │ to   │ msg# │ part/of  │ 14 bytes text│
     └──────┴──────┴─────────┴──────┴──────┴──────┴──────────┴──────────────┘
```

| Field | Meaning |
|---|---|
| `'M'` (0x4D) | "This is a MeshTalk packet." Everything else is ignored. |
| type | What kind of packet (see table below). |
| hops / TTL | How many devices it has passed through / how many more it may pass through. |
| from / to | 4-character node IDs. `to = FFFF` means "everyone". |
| msg# | A number that identifies this message. |
| part / of | Long messages are cut into pieces of 14 bytes. This says "piece 2 of 5". Up to 16 pieces. |

**Packet types:**

| # | Name | Used for |
|---|---|---|
| 1 | HELLO | "I'm here": name, type (ESP32/phone), uptime, relay count. Sent every ~20 s. |
| 2 | NBRS | "These are my direct neighbours and their signal strength." Every ~30 s. Feeds the admin map. |
| 3 | MSG | Private message to a friend. |
| 4 | BCAST | Broadcast to everyone. |
| 5 | SOS | Emergency alert to everyone. |
| 6 | ACK | "I received your message" (the ✓✓ receipt). |
| 7 | F-REQ | Friend request. |
| 8 | F-ACC | Friend request accepted. |
| 9 | F-REJ | Friend request declined. |

### 9.3 How a message finds its way (flooding)

1. The sender shouts the packet with **TTL = 7** (SOS: 15).
2. Every node that hears it:
   - **Already seen it in the last 20 s?** Ignore it (duplicate protection).
   - **Is it for me?** Show it. Done.
   - **Otherwise:** lower TTL by 1, raise hops by 1, wait a tiny random moment (so neighbours don't all shout at once), and shout it again. SOS skips the wait and goes to the front of the queue.
3. When TTL hits 0, the packet stops.

This is called **flooding**. It's simple, needs no routing tables, and automatically finds a path if one exists.

### 9.4 Making sure messages arrive

| Mechanism | How it works |
|---|---|
| **Receipts (ACK)** | The receiver sends an ACK back. Only then does the sender mark the message ✓✓. |
| **Retries** | No ACK yet? The sender tries again after 30 s, 60 s, 2 min, 4 min, then every 5 min. It gives up after **1 hour** (ESP32) or **24 hours** (phone). |
| **Store-and-forward** | Relays **keep** private messages and friend requests for other nodes for **10 minutes** (or until they see the ACK). |
| **"You're back!" trigger** | When any node hears from a device it hasn't heard from for over 60 s, it **immediately re-sends** everything it's holding for that device, instead of waiting for the next retry. |
| **No double messages** | If a retry arrives for a message you already have, you ACK again but don't show it twice. |

### 9.5 Who is my neighbour? How far away?

A packet with **hops = 0** came straight from its sender, with no relay in between. That sender is a **direct neighbour**, and the Bluetooth **signal strength (RSSI)** tells roughly how far away it is:

```
distance ≈ 10 ^ ((−59 − RSSI) / 25)      metres
```

This is only a rough guess (±50 %). Walls, bodies and pockets change the signal a lot.

### 9.6 Timing summary

| What | Value |
|---|---|
| Hop limit (TTL) | 7 (SOS: 15) |
| Duplicate memory | 20 s |
| HELLO beacon | every ~20 s |
| Neighbour report (NBRS) | every ~30 s |
| Counts as a neighbour for | 45 s after last direct packet |
| Counts as "back" after absence of | 60 s |
| Store-and-forward kept for | 10 min |
| Retry schedule | 30 s, 1 min, 2 min, 4 min, then every 5 min |
| Give up after | 1 h (ESP32), 24 h (phone) |
| Max message | 160 bytes (≈160 English letters) |
| Max name | 9 characters |
| Speed | roughly 3–5 s per hop for a full-length message; short messages are faster |

---

## 10. Troubleshooting

| Problem | Try this |
|---|---|
| **ESP32 keeps restarting / won't start with LCD attached** | The GPIO 12 problem. See the warning in [5.1](#51-wiring). |
| **LCD is lit but blank, or shows boxes** | Turn the contrast screw on the back of the LCD. |
| **LCD shows nothing at all** | Check SDA→13, SCL→12, VCC→5V, GND→GND. Some LCDs use address `0x3F`: change `0x27` to `0x3F` in `meshtalk.ino`. |
| **Wrong keys come out** | Reverse the order of the 8 keypad wires. |
| **Keys feel unresponsive** | Hold each key a little longer (0.25 s). To make it faster, lower `TAP_MS` (see [11](#11-settings-you-can-change)). |
| **Screen says "BLE init failed"** | Re-upload with board set to **ESP32 Dev Module**. Make sure it's a classic ESP32 (not S2, which has no Bluetooth). |
| **Upload stuck on "Connecting…"** | Hold the **BOOT** button while uploading. Try another USB cable. |
| **ESP32s show `Nb0`** | Wait 20–30 s. Move them closer (under 10 m, no walls). Make sure both have the same firmware. |
| **Phone doesn't see any nodes** | Bluetooth on? Permissions allowed? **Turn on Location** (some phones need it for Bluetooth scanning). Check the status text under "MeshTalk" at the top of the app. |
| **App says "Receive-only"** | This phone can't send Bluetooth advertisements (rare, old/cheap phones). It can still listen but not send. Use another phone. |
| **Messages stop when the phone screen is off** | Allow background running ([7.2](#72-first-start)) and don't swipe the "MeshTalk node active" notification away. |
| **Message stuck on 🕓** | The friend is out of reach. It will be delivered automatically when they come back in range. |
| **Can't send a private message** | You must be friends first (see [6.6](#66-friends-why-you-need-them)). |
| **Forgot the admin PIN** | Clear the app's storage (Android Settings → Apps → MeshTalk → Storage → Clear data). This also wipes messages and friends, and the PIN goes back to `1234`. |
| **Need to see what the ESP32 is doing** | Arduino IDE → Tools → Serial Monitor at 115200 baud. |

---

## 11. Settings you can change

### ESP32 (`firmware/meshtalk/meshtalk.ino`, near the top)

| Setting | Default | What it does |
|---|---|---|
| `I2C_SDA`, `I2C_SCL` | 13, 12 | LCD pins |
| `rowPins`, `colPins` | 32,33,25,26 / 18,19,5,17 | Keypad pins |
| `lcd(0x27, 16, 2)` | `0x27` | LCD address (try `0x3F` if blank) |
| `TAP_MS` | 250 | How long (ms) a key must be held to count. Lower = more sensitive. |
| `DIGIT_HOLD_MS` | 1000 | Hold this long to type a digit |
| `MULTITAP_MS` | 1000 | Pause that locks in a letter |
| `BACKLIGHT_MS` | 60000 | Screen light turns off after this idle time |
| `ADV_HOLD_MS` | 250 | How long each packet is shouted |

After changing anything, upload again.

### Android (`android/app/src/main/java/com/meshtalk/Mesh.kt`)

| Setting | Default | What it does |
|---|---|---|
| `rssiAt1m` | −59 | Signal strength at 1 metre (for distance estimates) |
| `pathLoss` | 2.5 | How fast signal fades (2 = open field, 3–4 = indoors with walls) |

> ⚠️ **Don't change** anything in the "protocol" section (packet size, TTL, message types, timings) on one side only. The ESP32 code and the app must match, or they won't understand each other.

---

## 12. Rebuild the app yourself

You only need this if you change the Android code. A ready APK is already in `dist/`.

The build tools (Java 17, Android SDK, Gradle) were installed to `%LOCALAPPDATA%\meshtalk-tools\`. In a Command Prompt:

```bat
cd android
set JAVA_HOME=%LOCALAPPDATA%\meshtalk-tools\jdk

gradlew testDebugUnitTest     :: run the automatic tests
gradlew assembleRelease       :: build the app
```

The new APK appears at `android/app/build/outputs/apk/release/app-release.apk`.

Or open the `android` folder in **Android Studio** and press ▶ Run.

> 🔑 **Back up `android/meshtalk-release.jks` and `android/keystore.properties`** (USB stick, cloud drive).
> They're the app's signature. If you lose them, future versions **can't install as an update** over the current one. Users would have to uninstall first and would lose their messages. Never share them publicly (they're already excluded from git).

---

## 13. Limits and safety

- **Messages are not encrypted.** Anyone with MeshTalk can read broadcasts and SOS, and a tech-savvy person could read private messages or pretend to be another node. **Don't send passwords or secrets.**
- **ESP32 message memory:** each ESP32 keeps its **last 20 messages** in memory, and they're **lost when it's switched off**. Its name and friends list are saved permanently.
- **Phone storage:** keeps the last 1000 messages, saved permanently.
- **Range:** usually **10–30 m per hop** indoors and up to ~50–100 m outdoors with clear line of sight. Each extra node extends the reach.
- **Speed:** this is a text network for short messages, not for photos or voice.
- **Crowding:** designed for tens of nodes. Hundreds of nodes close together will slow things down.
- **Emergencies:** MeshTalk is a helpful extra, **not a replacement for official emergency services.** Always use real emergency numbers when you can.

---

## 14. Code map (for developers)

### ESP32: `firmware/meshtalk/meshtalk.ino` (one file, sections in this order)

| Section | What it does |
|---|---|
| hardware | LCD, keypad and pin setup |
| tuning knobs | Timings you can change (see [11](#11-settings-you-can-change)) |
| protocol | Packet format constants (must match the app's `Proto.kt`) |
| state | All data: nodes, friends, requests, messages, outbox, caches, send queue |
| helpers | Small utilities |
| BLE radio | Talks directly to the ESP32's Bluetooth: scans, advertises one packet at a time |
| mesh core | Sending, fragmenting, duplicate check, relaying, store-and-forward, ACKs, retries, beacons, delivering messages |
| UI | Screens, menu, lists, text editor (multi-tap), pop-ups, backlight, key reading |
| setup / loop | Start-up, then the main loop: read packets → keys → beacons → retries → send → draw screen |

> All types are declared before the first function on purpose. Arduino auto-generates function declarations above the first function, and they'd break otherwise.

### Android: `android/app/src/main/java/com/meshtalk/`

| File | What it does |
|---|---|
| `Proto.kt` | Packet format: build, read, split into pieces, join pieces back. Mirrors the ESP32. |
| `Mesh.kt` | The "brain": same mesh rules as the ESP32 (relay, dedup, ACK, retry, store-and-forward, beacons), plus friends, messages, saving to storage, PIN. |
| `BleRadio.kt` | Bluetooth: scans for packets and advertises the send queue one packet at a time. Restarts itself if Bluetooth is toggled. |
| `MeshService.kt` | Background service that keeps the node running and shows notifications (messages, requests, SOS alarm). |
| `MainActivity.kt` | App start: permissions, Bluetooth, name setup, main screen with tabs and SOS button. |
| `Screens.kt` | Chats, chat window, Nearby, Requests, SOS dialog, Settings. |
| `Admin.kt` | Admin PIN lock, network graph, node/link tables, live packet log. |
| `app/src/test/.../ProtoTest.kt` | Automatic tests, including one that checks the app builds packets **byte-for-byte** the same as the ESP32. |

---

## 15. Glossary

| Word | Plain meaning |
|---|---|
| **Node** | Any device in the network (ESP32 or phone). |
| **Mesh** | A network where devices connect to each other directly, with no central router. |
| **Hop** | One jump from one device to the next. |
| **Relay / forward** | Passing someone else's message along. |
| **TTL** (time to live) | How many more hops a message is allowed to make. |
| **Flooding** | Everyone passes every new message to everyone nearby. |
| **Broadcast** | A message to everyone. |
| **ACK** | A receipt saying "I got it". |
| **Store-and-forward** | Holding a message until the receiver is reachable again. |
| **BLE** | Bluetooth Low Energy, the power-saving kind of Bluetooth. |
| **Advertisement** | A short packet a BLE device shouts to anyone listening. |
| **RSSI** | Received signal strength, in dBm. Closer to 0 = stronger (−50 is strong, −90 is weak). |
| **Firmware** | The program running on the ESP32. |
| **APK** | An Android app installation file. |
| **GPIO** | A numbered pin on the ESP32 you connect wires to. |
| **I2C** | A 2-wire connection (SDA + SCL) used by the LCD. |
