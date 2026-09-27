#include <Arduino.h>
// MeshTalk ESP32 node (single-file sketch) — BLE advertising mesh, 4x4 keypad (multi-tap) + 16x2 I2C LCD.
// Board: "ESP32 Dev Module" (default partition is fine).
// Protocol is shared with the Android app — see README.md "Protocol". Change both sides together.

#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <Keypad.h>
#include <Preferences.h>
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"

// ================= hardware =================
#define I2C_SDA 13
#define I2C_SCL 12  // NOTE: GPIO12 is a boot strapping pin, see README if the board won't boot.
LiquidCrystal_I2C lcd(0x27, 16, 2);
#define BUZZER_PIN 15  // active buzzer, HIGH = on. GPIO15 is a strapping pin too: may chirp at boot, harmless.

const byte ROWS = 4, COLS = 4;
char keys[ROWS][COLS] = {
  {'1', '2', '3', 'A'},
  {'4', '5', '6', 'B'},
  {'7', '8', '9', 'C'},
  {'*', '0', '#', 'D'}
};
byte rowPins[ROWS] = {32, 33, 25, 26};
byte colPins[COLS] = {18, 19, 5, 17};
Keypad keypad(makeKeymap(keys), rowPins, colPins, ROWS, COLS);

// ================= tuning knobs =================
#define TAP_MS 250          // key must be held this long to register (filters bumps)
#define DIGIT_HOLD_MS 1000  // keep holding a digit key -> types the digit itself
#define MULTITAP_MS 1000    // pause that commits a multi-tap letter
#define REPEAT_MS 200       // A/B auto-repeat speed while held
#define BACKLIGHT_MS 60000  // backlight off after idle
#define ADV_HOLD_MS 250     // how long each packet is advertised (~10 transmissions)
#define SOS_HOLD_MS 600
#define BEEP_MS 200         // buzzer length for a normal incoming message

// ================= protocol (keep in sync with app) =================
#define COMPANY_ID 0xFFFF
#define MAGIC 0x4D
#define PKT_LEN 24
#define HDR_LEN 10
#define CHUNK (PKT_LEN - HDR_LEN)  // 14 payload bytes per fragment
#define MAX_FRAGS 16
#define BCAST 0xFFFF
#define TTL_DEFAULT 7
#define TTL_SOS 15
#define MAX_TXT 160
#define NAME_LEN 9
#define DEDUP_MS 20000UL
#define HELLO_MS 20000UL
#define NBRS_MS 30000UL
#define NEIGHBOR_MS 45000UL
#define ABSENT_MS 60000UL
#define NODE_SHOW_MS 300000UL
#define SF_KEEP_MS 600000UL
#define OUT_EXPIRE_MS 3600000UL

enum { T_HELLO = 1, T_NBRS, T_MSG, T_BCAST, T_SOS, T_ACK, T_FREQ, T_FACC, T_FREJ };
// ================= state =================
// Every type is declared before the first function so this file also compiles as a plain .ino
// (Arduino inserts auto-generated prototypes above the first function).
struct RawPkt { uint8_t d[PKT_LEN]; int8_t rssi; };
enum Screen { SC_HOME, SC_MENU, SC_INBOX, SC_READ, SC_PICK, SC_EDIT, SC_FRIENDS, SC_FRIEND_ACT,
              SC_NEARBY, SC_REQS, SC_REQ_ACT, SC_SOS, SC_SETTINGS, SC_INFO };
Preferences prefs;
uint16_t myId;
char myName[NAME_LEN + 1];
uint16_t nextMsgId;
uint32_t relayCount;

struct Node { uint16_t id; char name[NAME_LEN + 1]; uint8_t kind, hops; int8_t rssi; uint32_t lastSeen, lastDirect; };
#define NODE_MAX 32
Node nodes[NODE_MAX];

struct Friend { uint16_t id; char name[NAME_LEN + 1]; };
#define FRIEND_MAX 16
Friend friends[FRIEND_MAX];
int nFriends;

struct Req { uint16_t id; char name[NAME_LEN + 1]; };
#define REQ_MAX 8
Req reqs[REQ_MAX];  // incoming, waiting for the user
int nReqs;
uint16_t reqOut[REQ_MAX];  // ids we asked; only these may "accept" us
int nReqOut;

enum { K_IN, K_OUT, K_BC_IN, K_BC_OUT, K_SOS_IN, K_SOS_OUT };
enum { ST_UNREAD, ST_READ, ST_PENDING, ST_SENT, ST_DELIVERED, ST_FAILED };
struct Msg { uint16_t peer, id; uint8_t kind, status; uint32_t t; char text[MAX_TXT + 1]; };
#define MSG_MAX 20
Msg msgs[MSG_MAX];
int msgHead, msgN;

struct Out { bool used; uint8_t type; uint16_t dst, id; char text[MAX_TXT + 1]; uint32_t created, nextTry; uint8_t tries; };
#define OUT_MAX 6
Out outbox[OUT_MAX];

struct Seen { uint16_t src, id; uint8_t frag; uint32_t t; };
#define SEEN_MAX 128
Seen seen[SEEN_MAX];
int seenHead;

struct Done { uint16_t src, id; };
#define DONE_MAX 32
Done done[DONE_MAX];  // reliable messages already delivered to us (for re-ACK)
int doneHead;

struct Rx { bool used; uint16_t src, id, dst; uint8_t type, cnt, hops; uint16_t mask; uint32_t t; uint8_t buf[MAX_FRAGS * CHUNK]; };
#define RX_MAX 6
Rx rxs[RX_MAX];

struct Sf { bool used; uint8_t d[PKT_LEN]; uint32_t t; };
#define SF_MAX 48
Sf sfCache[SF_MAX];  // store-and-forward: reliable packets for others, until ACKed

struct Tx { bool used; uint8_t d[PKT_LEN]; uint32_t notBefore, seq; uint16_t hold; };
#define TX_MAX 40
Tx txq[TX_MAX];
uint32_t txSeq;

// ================= helpers =================
static bool reliable(uint8_t t) { return t == T_MSG || t == T_FREQ || t == T_FACC || t == T_FREJ; }

static inline uint16_t get16(const uint8_t *p) { return p[0] | (p[1] << 8); }
static inline void put16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }

// The core frees BLE RAM at boot unless someone claims it; we use raw GAP, not the BLE library.
extern "C" bool bleInUse(void) { return true; }

static int slotOf(int i) { return (msgHead - 1 - i + MSG_MAX) % MSG_MAX; }  // i=0 newest

// ================= BLE radio =================
static QueueHandle_t rxQueue;
static esp_ble_adv_params_t advParams;
static bool advOn;
static uint32_t advUntil;

static void gapCb(esp_gap_ble_cb_event_t ev, esp_ble_gap_cb_param_t *p) {
  if (ev == ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT) {
    esp_ble_gap_start_scanning(0);
  } else if (ev == ESP_GAP_BLE_SCAN_RESULT_EVT) {
    if (p->scan_rst.search_evt == ESP_GAP_SEARCH_INQ_CMPL_EVT) { esp_ble_gap_start_scanning(0); return; }
    if (p->scan_rst.search_evt != ESP_GAP_SEARCH_INQ_RES_EVT) return;
    const uint8_t *a = p->scan_rst.ble_adv;
    int n = p->scan_rst.adv_data_len + p->scan_rst.scan_rsp_len;
    for (int i = 0; i + 1 < n;) {
      int len = a[i];
      if (!len || i + 1 + len > n) break;
      if (len == 3 + PKT_LEN && a[i + 1] == 0xFF && get16(a + i + 2) == COMPANY_ID && a[i + 4] == MAGIC) {
        RawPkt r;
        memcpy(r.d, a + i + 4, PKT_LEN);
        r.rssi = p->scan_rst.rssi;
        xQueueSend(rxQueue, &r, 0);  // drop if full, never block the BT task
        break;
      }
      i += len + 1;
    }
  }
}

static bool bleInit() {
  rxQueue = xQueueCreate(48, sizeof(RawPkt));
  if (!btStart()) return false;
  if (esp_bluedroid_init() != ESP_OK || esp_bluedroid_enable() != ESP_OK) return false;
  esp_ble_gap_register_callback(gapCb);
  advParams.adv_int_min = 0x20;  // 20 ms
  advParams.adv_int_max = 0x30;  // 30 ms
  advParams.adv_type = ADV_TYPE_NONCONN_IND;
  advParams.own_addr_type = BLE_ADDR_TYPE_PUBLIC;
  advParams.channel_map = ADV_CHNL_ALL;
  advParams.adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY;
  esp_ble_scan_params_t sp = {};
  sp.scan_type = BLE_SCAN_TYPE_PASSIVE;
  sp.own_addr_type = BLE_ADDR_TYPE_PUBLIC;
  sp.scan_filter_policy = BLE_SCAN_FILTER_ALLOW_ALL;
  sp.scan_interval = 0x50;  // 50 ms
  sp.scan_window = 0x30;    // 30 ms, leaves radio time for advertising
  sp.scan_duplicate = BLE_SCAN_DUPLICATE_DISABLE;
  return esp_ble_gap_set_scan_params(&sp) == ESP_OK;
}

static void advSet(const uint8_t *pkt) {
  static uint8_t raw[4 + PKT_LEN];
  raw[0] = 3 + PKT_LEN;
  raw[1] = 0xFF;
  put16(raw + 2, COMPANY_ID);
  memcpy(raw + 4, pkt, PKT_LEN);
  esp_ble_gap_config_adv_data_raw(raw, sizeof raw);
  if (!advOn) { esp_ble_gap_start_advertising(&advParams); advOn = true; }
}

static bool txPush(const uint8_t *d, uint32_t delayMs, uint16_t hold) {
  for (auto &t : txq) if (!t.used) {
    memcpy(t.d, d, PKT_LEN);
    t.notBefore = millis() + delayMs;
    t.seq = txSeq++;
    t.hold = hold;
    t.used = true;
    return true;
  }
  return false;  // full: relays get dropped, own reliable messages are retried by the outbox
}

static void txService() {
  uint32_t now = millis();
  if ((int32_t)(now - advUntil) < 0) return;
  Tx *best = nullptr;
  for (auto &t : txq) {
    if (!t.used || (int32_t)(now - t.notBefore) < 0) continue;
    bool sos = t.d[1] == T_SOS, bestSos = best && best->d[1] == T_SOS;
    if (!best || (sos && !bestSos) || (sos == bestSos && t.seq < best->seq)) best = &t;
  }
  if (best) {
    advSet(best->d);
    advUntil = now + best->hold;
    best->used = false;
  } else if (advOn) {
    esp_ble_gap_stop_advertising();  // never keep repeating a stale packet
    advOn = false;
  }
}

// ================= mesh core =================
static void hdr(uint8_t *p, uint8_t type, uint8_t ttl, uint16_t dst, uint16_t id, uint8_t frag, uint8_t cnt) {
  memset(p, 0, PKT_LEN);
  p[0] = MAGIC;
  p[1] = type;
  p[2] = ttl & 0x0F;  // hops (high nibble) = 0
  put16(p + 3, myId);
  put16(p + 5, dst);
  put16(p + 7, id);
  p[9] = (frag << 4) | (cnt - 1);
}

static void sendData(uint8_t type, uint16_t dst, uint16_t id, const uint8_t *data, int n) {
  int cnt = n ? (n + CHUNK - 1) / CHUNK : 1;
  if (cnt > MAX_FRAGS) cnt = MAX_FRAGS;
  uint8_t ttl = type == T_SOS ? TTL_SOS : TTL_DEFAULT;
  for (int f = 0; f < cnt; f++) {
    uint8_t p[PKT_LEN];
    hdr(p, type, ttl, dst, id, f, cnt);
    int off = f * CHUNK, len = min(CHUNK, n - off);
    if (len > 0) memcpy(p + HDR_LEN, data + off, len);
    txPush(p, 0, type == T_SOS ? SOS_HOLD_MS : ADV_HOLD_MS);
  }
}

static void sendAck(uint16_t to, uint16_t id, uint8_t type) {
  uint8_t d[3];
  put16(d, id);
  d[2] = type;
  sendData(T_ACK, to, nextMsgId++, d, 3);
}

static bool seenBefore(uint16_t src, uint16_t id, uint8_t frag) {
  uint32_t now = millis();
  for (auto &s : seen)
    if (s.t && s.src == src && s.id == id && s.frag == frag && now - s.t < DEDUP_MS) return true;
  seen[seenHead] = {src, id, frag, now ? now : 1};
  seenHead = (seenHead + 1) % SEEN_MAX;
  return false;
}

static Node *findNode(uint16_t id, bool create) {
  Node *oldest = &nodes[0];
  for (auto &n : nodes) {
    if (n.id == id && n.lastSeen) return &n;
    if (n.lastSeen < oldest->lastSeen) oldest = &n;
  }
  if (!create) return nullptr;
  memset(oldest, 0, sizeof(Node));
  oldest->id = id;
  return oldest;
}

static int friendIdx(uint16_t id) {
  for (int i = 0; i < nFriends; i++) if (friends[i].id == id) return i;
  return -1;
}

static const char *nameOf(uint16_t id) {
  static char buf[8];
  if (id == myId) return myName;
  int f = friendIdx(id);
  if (f >= 0) return friends[f].name;
  Node *n = findNode(id, false);
  if (n && n->name[0]) return n->name;
  snprintf(buf, sizeof buf, "#%04X", id);
  return buf;
}

static void saveFriends() {
  prefs.putBytes("friends", friends, sizeof(Friend) * nFriends);
}

static void addFriend(uint16_t id, const char *name) {
  int f = friendIdx(id);
  if (f < 0) {
    if (nFriends >= FRIEND_MAX) return;
    f = nFriends++;
    friends[f].id = id;
  }
  strlcpy(friends[f].name, name && name[0] ? name : nameOf(id), NAME_LEN + 1);
  saveFriends();
}

static void removeFriend(int i) {
  friends[i] = friends[--nFriends];
  saveFriends();
}

static void toAscii(const uint8_t *s, int n, char *out, int max) {
  int j = 0;
  for (int i = 0; i < n && s[i] && j < max; i++) {
    uint8_t c = s[i];
    if (c < 0x80) out[j++] = c < 32 ? ' ' : c;
    else if ((c & 0xC0) != 0x80) out[j++] = '?';  // one '?' per UTF-8 character
  }
  out[j] = 0;
}

static Msg *addMsg(uint16_t peer, uint16_t id, uint8_t kind, uint8_t status, const char *text) {
  Msg &m = msgs[msgHead];
  m.peer = peer;
  m.id = id;
  m.kind = kind;
  m.status = status;
  m.t = millis();
  strlcpy(m.text, text, sizeof m.text);
  msgHead = (msgHead + 1) % MSG_MAX;
  if (msgN < MSG_MAX) msgN++;
  return &m;
}

static int unreadCount() {
  int c = 0;
  for (int i = 0; i < msgN; i++) c += msgs[slotOf(i)].status == ST_UNREAD;
  return c;
}

static int pendingCount() {
  int c = 0;
  for (auto &o : outbox) c += o.used;
  return c;
}

// --- UI hooks (defined in UI section) ---
void popupMsg(int slot);
void popupReq(uint16_t id);
void toast(const char *a, const char *b = "");
void markDirty();

static bool queueOut(uint8_t type, uint16_t dst, const char *text, uint16_t *idOut) {
  for (auto &o : outbox) if (!o.used) {
    o.used = true;
    o.type = type;
    o.dst = dst;
    o.id = nextMsgId++;
    strlcpy(o.text, text, sizeof o.text);
    o.created = millis();
    o.nextTry = o.created;
    o.tries = 0;
    if (idOut) *idOut = o.id;
    return true;
  }
  return false;
}

static void outboxService() {
  uint32_t now = millis();
  for (auto &o : outbox) {
    if (!o.used) continue;
    if (now - o.created > OUT_EXPIRE_MS) {
      for (int i = 0; i < msgN; i++) {
        Msg &m = msgs[slotOf(i)];
        if (m.peer == o.dst && m.id == o.id && m.status == ST_PENDING) m.status = ST_FAILED;
      }
      if (o.type == T_MSG) toast("Not delivered:", nameOf(o.dst));
      o.used = false;
      continue;
    }
    if ((int32_t)(now - o.nextTry) < 0) continue;
    sendData(o.type, o.dst, o.id, (const uint8_t *)o.text, strlen(o.text));
    uint32_t backoff = 30000UL << min<int>(o.tries, 3);  // 30s, 60s, 120s, 240s...
    o.nextTry = now + min<uint32_t>(backoff, 300000UL);
    o.tries++;
  }
}

// A node reappeared after being absent: flush anything we hold for it right now.
static void onNodeBack(uint16_t id) {
  for (auto &o : outbox) if (o.used && o.dst == id) o.nextTry = millis();
  for (auto &s : sfCache) if (s.used && get16(s.d + 5) == id) {
    uint8_t p[PKT_LEN];
    memcpy(p, s.d, PKT_LEN);
    p[2] = (p[2] & 0xF0) | TTL_DEFAULT;
    txPush(p, random(20, 200), ADV_HOLD_MS);
  }
}

static void sfStore(const uint8_t *d) {
  Sf *slot = &sfCache[0];
  for (auto &s : sfCache) {
    if (s.used && memcmp(s.d + 3, d + 3, 7) == 0) return;  // same src/dst/id/frag already held
    if (!s.used || s.t < slot->t) slot = &s;
  }
  memcpy(slot->d, d, PKT_LEN);
  slot->t = millis();
  slot->used = true;
}

static void sfAcked(uint16_t origSrc, uint16_t origDst, uint16_t id) {
  for (auto &s : sfCache)
    if (s.used && get16(s.d + 3) == origSrc && get16(s.d + 5) == origDst && get16(s.d + 7) == id) s.used = false;
}

static bool wasDone(uint16_t src, uint16_t id) {
  for (auto &d : done) if (d.src == src && d.id == id) return true;
  done[doneHead] = {src, id};
  doneHead = (doneHead + 1) % DONE_MAX;
  return false;
}

// Complete (reassembled) payload addressed to us or broadcast.
static void deliver(uint8_t type, uint16_t src, uint16_t dst, uint16_t id, const uint8_t *data, int n) {
  char text[MAX_TXT + 1];
  if (type == T_HELLO) {
    Node *nd = findNode(src, true);
    nd->kind = data[0];
    toAscii(data + 5, NAME_LEN, nd->name, NAME_LEN);
    int f = friendIdx(src);
    if (f >= 0 && nd->name[0] && strcmp(friends[f].name, nd->name)) addFriend(src, nd->name);
    return;
  }
  if (type == T_ACK) {
    if (dst != myId) return;
    uint16_t acked = get16(data);
    for (auto &o : outbox) if (o.used && o.dst == src && o.id == acked) o.used = false;
    for (int i = 0; i < msgN; i++) {
      Msg &m = msgs[slotOf(i)];
      if (m.peer == src && m.id == acked && m.status == ST_PENDING) { m.status = ST_DELIVERED; markDirty(); }
    }
    if (data[2] == T_FREQ) toast("Request sent to", nameOf(src));
    return;
  }
  if (type == T_BCAST || type == T_SOS) {
    toAscii(data, n, text, MAX_TXT);
    Msg *m = addMsg(src, id, type == T_SOS ? K_SOS_IN : K_BC_IN, ST_UNREAD, text);
    popupMsg(m - msgs);
    return;
  }
  if (!reliable(type) || dst != myId) return;
  if (type == T_MSG && friendIdx(src) < 0) return;  // private chat needs an accepted friend request
  sendAck(src, id, type);
  if (wasDone(src, id)) return;  // a retry of something we already have
  toAscii(data, n, text, MAX_TXT);
  if (type == T_MSG) {
    Msg *m = addMsg(src, id, K_IN, ST_UNREAD, text);
    popupMsg(m - msgs);
  } else if (type == T_FREQ) {
    Node *nd = findNode(src, true);
    if (text[0]) strlcpy(nd->name, text, NAME_LEN + 1);
    if (friendIdx(src) >= 0) { queueOut(T_FACC, src, myName, nullptr); return; }  // they lost us; re-accept
    for (int i = 0; i < nReqs; i++) if (reqs[i].id == src) return;
    if (nReqs < REQ_MAX) { reqs[nReqs].id = src; strlcpy(reqs[nReqs].name, nameOf(src), NAME_LEN + 1); nReqs++; }
    popupReq(src);
  } else {
    bool asked = false;
    for (int i = 0; i < nReqOut; i++) if (reqOut[i] == src) { asked = true; reqOut[i] = reqOut[--nReqOut]; break; }
    if (type == T_FACC && (asked || friendIdx(src) >= 0)) {
      addFriend(src, text);
      toast("Now friends:", nameOf(src));
    } else if (type == T_FREJ && asked) {
      toast("Declined by", nameOf(src));
    }
  }
}

static void handlePacket(const RawPkt &r) {
  const uint8_t *d = r.d;
  uint8_t type = d[1], ttl = d[2] & 0x0F, hops = d[2] >> 4;
  uint16_t src = get16(d + 3), dst = get16(d + 5), id = get16(d + 7);
  uint8_t frag = d[9] >> 4, cnt = (d[9] & 0x0F) + 1;
  if (src == myId || src == 0 || src == BCAST || frag >= cnt) return;
  if (seenBefore(src, id, frag)) return;

  uint32_t now = millis();
  Node *nd = findNode(src, true);
  bool wasAbsent = !nd->lastSeen || now - nd->lastSeen > ABSENT_MS;
  nd->lastSeen = now;
  nd->hops = hops;
  if (hops == 0) { nd->lastDirect = now; nd->rssi = r.rssi; }
  if (wasAbsent) onNodeBack(src);

  if (type == T_ACK) sfAcked(dst, src, get16(d + HDR_LEN));

  // relay
  if (dst != myId && ttl > 1) {
    uint8_t p[PKT_LEN];
    memcpy(p, d, PKT_LEN);
    p[2] = (min(hops + 1, 15) << 4) | (ttl - 1);
    bool sos = type == T_SOS;
    if (txPush(p, sos ? 0 : random(20, 150), sos ? SOS_HOLD_MS : ADV_HOLD_MS)) relayCount++;
    if (reliable(type) && dst != BCAST) sfStore(d);
  }
  if (dst != myId && dst != BCAST) return;

  // reassemble
  if (cnt == 1) { deliver(type, src, dst, id, d + HDR_LEN, CHUNK); return; }
  Rx *rx = nullptr, *freeSlot = nullptr;
  for (auto &x : rxs) {
    if (x.used && now - x.t > 30000) x.used = false;
    if (x.used && x.src == src && x.id == id) rx = &x;
    if (!x.used && !freeSlot) freeSlot = &x;
  }
  if (!rx) {
    if (!freeSlot) return;
    rx = freeSlot;
    memset(rx, 0, sizeof(Rx));
    rx->used = true;
    rx->src = src; rx->id = id; rx->dst = dst; rx->type = type; rx->cnt = cnt; rx->t = now;
  }
  memcpy(rx->buf + frag * CHUNK, d + HDR_LEN, CHUNK);
  rx->mask |= 1 << frag;
  if (rx->mask == (1u << cnt) - 1) {
    rx->used = false;
    deliver(type, src, dst, id, rx->buf, cnt * CHUNK);
  }
}

static uint32_t nextHello, nextNbrs;

static void beaconService() {
  uint32_t now = millis();
  if ((int32_t)(now - nextHello) >= 0) {
    nextHello = now + HELLO_MS + random(0, 5000);
    uint8_t d[CHUNK] = {0};
    d[0] = 0;  // kind: 0 = ESP node, 1 = phone
    put16(d + 1, now / 60000);
    put16(d + 3, min<uint32_t>(relayCount, 0xFFFF));
    memcpy(d + 5, myName, strnlen(myName, NAME_LEN));
    sendData(T_HELLO, BCAST, nextMsgId++, d, CHUNK);
  }
  if ((int32_t)(now - nextNbrs) >= 0) {
    nextNbrs = now + NBRS_MS + random(0, 5000);
    uint8_t d[1 + NODE_MAX * 3];
    int c = 0;
    for (auto &n : nodes)
      if (n.lastDirect && now - n.lastDirect < NEIGHBOR_MS && c < 20) {
        put16(d + 1 + c * 3, n.id);
        d[3 + c * 3] = (uint8_t)n.rssi;
        c++;
      }
    d[0] = c;
    if (c) sendData(T_NBRS, BCAST, nextMsgId++, d, 1 + c * 3);
  }
}

static int neighborCount() {
  int c = 0;
  uint32_t now = millis();
  for (auto &n : nodes) c += n.lastDirect && now - n.lastDirect < NEIGHBOR_MS;
  return c;
}

// ================= UI =================
enum { G_MAIL = 1, G_OK, G_WAIT, G_SOS, G_FRIEND, G_FAIL, G_BC };
uint8_t glyphs[7][8] = {
  {0x00, 0x1F, 0x1B, 0x15, 0x11, 0x11, 0x1F, 0x00},  // mail
  {0x00, 0x01, 0x03, 0x16, 0x1C, 0x08, 0x00, 0x00},  // check
  {0x00, 0x0E, 0x15, 0x17, 0x11, 0x0E, 0x00, 0x00},  // clock
  {0x04, 0x0E, 0x0E, 0x0E, 0x1F, 0x00, 0x04, 0x00},  // bell
  {0x0E, 0x0E, 0x04, 0x1F, 0x04, 0x0A, 0x11, 0x00},  // person
  {0x00, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x00, 0x00},  // x
  {0x15, 0x15, 0x0E, 0x04, 0x04, 0x04, 0x04, 0x00},  // antenna
};

enum { P_NONE, P_MSG, P_REQ, P_SOS, P_TOAST };
enum { ED_MSG, ED_BCAST, ED_NAME };
enum { EV_TAP, EV_HOLD };

Screen screen = SC_HOME, readBack = SC_INBOX;
int sel, scroll;
int readSlot = -1, actIdx;
uint16_t reqActId;
bool dirty = true, lightOn = true;
uint32_t lastInput;

int popup = P_NONE;
int popupSlot;
uint16_t popupId;
uint32_t popupUntil, buzzUntil;
char toastA[17], toastB[17];

// editor
int edTarget;
uint16_t edDst;
char edBuf[MAX_TXT + 1];
int edLen, edMax, edMode;  // mode: 0 abc, 1 Abc, 2 ABC, 3 123
char edPendKey;
int edPendIdx;
uint32_t edPendAt;
char edTitle[16];

void markDirty() { dirty = true; }

void wake() {
  lastInput = millis();
  if (!lightOn) { lcd.backlight(); lightOn = true; }
}

void toast(const char *a, const char *b) {
  if (popup == P_SOS) return;
  strlcpy(toastA, a, sizeof toastA);
  strlcpy(toastB, b, sizeof toastB);
  popup = P_TOAST;
  popupUntil = millis() + 2500;
  wake();
  dirty = true;
}

void popupMsg(int slot) {
  bool sos = msgs[slot].kind == K_SOS_IN;
  if (popup == P_SOS && !sos) { dirty = true; return; }
  popup = sos ? P_SOS : P_MSG;
  popupSlot = slot;
  popupUntil = millis() + (sos ? 120000 : 15000);
  buzzUntil = millis() + BEEP_MS;  // SOS keeps buzzing in uiService() until acknowledged
  wake();
  dirty = true;
}

void popupReq(uint16_t id) {
  if (popup == P_SOS) return;
  popup = P_REQ;
  popupId = id;
  popupUntil = millis() + 30000;
  wake();
  dirty = true;
}

void go(Screen s) {
  screen = s;
  sel = scroll = 0;
  dirty = true;
}

void openEditor(int target, uint16_t dst, const char *title) {
  if (target != edTarget || dst != edDst || target == ED_NAME) edLen = 0;  // keep draft for same recipient
  edTarget = target;
  edDst = dst;
  strlcpy(edTitle, title, sizeof edTitle);  // copy: callers pass temporaries
  edMax = target == ED_NAME ? NAME_LEN : MAX_TXT;
  if (target == ED_NAME) { strlcpy(edBuf, myName, sizeof edBuf); edLen = strlen(edBuf); }
  edBuf[edLen] = 0;
  edMode = edLen ? 0 : 1;
  edPendKey = 0;
  go(SC_EDIT);
}

const char *menuItems[] = {"Inbox", "New message", "Broadcast", "Friends", "Nearby nodes", "Requests", "SOS alert", "Settings"};
const int MENU_N = 8;
const char *settingItems[] = {"Change name", "Node info", "Clear inbox"};

// nodes seen recently, excluding us
int nearbyList(int *out) {
  int c = 0;
  uint32_t now = millis();
  for (int i = 0; i < NODE_MAX; i++)
    if (nodes[i].lastSeen && now - nodes[i].lastSeen < NODE_SHOW_MS && nodes[i].id != myId) out[c++] = i;
  return c;
}

void fmtAgo(uint32_t t, char *out) {
  uint32_t s = (millis() - t) / 1000;
  if (s < 60) strcpy(out, "now");
  else if (s < 3600) sprintf(out, "%lum", (unsigned long)(s / 60));
  else if (s < 86400) sprintf(out, "%luh", (unsigned long)(s / 3600));
  else sprintf(out, "%lud", (unsigned long)(s / 86400));
}

char msgGlyph(const Msg &m) {
  switch (m.kind) {
    case K_SOS_IN: case K_SOS_OUT: return G_SOS;
    case K_BC_IN: case K_BC_OUT: return G_BC;
  }
  switch (m.status) {
    case ST_UNREAD: return G_MAIL;
    case ST_PENDING: return G_WAIT;
    case ST_DELIVERED: return G_OK;
    case ST_FAILED: return G_FAIL;
  }
  return ' ';
}

int listCount() {
  int tmp[NODE_MAX];
  switch (screen) {
    case SC_MENU: return MENU_N;
    case SC_INBOX: return msgN;
    case SC_PICK: case SC_FRIENDS: return nFriends;
    case SC_FRIEND_ACT: return 2;
    case SC_NEARBY: return nearbyList(tmp);
    case SC_REQS: return nReqs;
    case SC_SETTINGS: return 3;
    default: return 0;
  }
}

void listItem(int i, char *o) {  // writes up to 15 chars
  switch (screen) {
    case SC_MENU: snprintf(o, 16, "%d %s", i + 1, menuItems[i]); break;
    case SC_INBOX: {
      Msg &m = msgs[slotOf(i)];
      const char *who = (m.kind == K_OUT || m.kind == K_BC_OUT || m.kind == K_SOS_OUT) ? "Me" : nameOf(m.peer);
      snprintf(o, 16, "%c%.5s:%s", msgGlyph(m), who, m.text);
      break;
    }
    case SC_PICK: case SC_FRIENDS: snprintf(o, 16, "%c%s", G_FRIEND, friends[i].name); break;
    case SC_FRIEND_ACT: strcpy(o, i ? "Remove friend" : "Send message"); break;
    case SC_NEARBY: {
      int idx[NODE_MAX];
      nearbyList(idx);
      Node &n = nodes[idx[i]];
      char tail[8];
      if (n.lastDirect && millis() - n.lastDirect < NEIGHBOR_MS) snprintf(tail, sizeof tail, "%d", n.rssi);
      else snprintf(tail, sizeof tail, "%dhop", n.hops + 1);
      snprintf(o, 16, "%c%-9.9s%5s", friendIdx(n.id) >= 0 ? (char)G_FRIEND : ' ', nameOf(n.id), tail);
      break;
    }
    case SC_REQS: snprintf(o, 16, "%c%s", G_FRIEND, reqs[i].name); break;
    case SC_SETTINGS: strcpy(o, settingItems[i]); break;
    default: o[0] = 0;
  }
}

// Wrap message into 16-char lines for the reader.
int readLines(char lines[][17], int maxLines) {
  Msg &m = msgs[readSlot];
  char ago[6];
  fmtAgo(m.t, ago);
  bool out = m.kind == K_OUT || m.kind == K_BC_OUT || m.kind == K_SOS_OUT;
  snprintf(lines[0], 17, "%c%s%.8s %s", msgGlyph(m), out ? ">" : "", out ? (m.kind == K_OUT ? nameOf(m.peer) : "All") : nameOf(m.peer), ago);
  int n = 1, len = strlen(m.text);
  for (int off = 0; off < len && n < maxLines; off += 16, n++) snprintf(lines[n], 17, "%.16s", m.text + off);
  return n;
}

char lastL0[17], lastL1[17];
int lastCurMode = -1, lastCurCol = -1;

void putLines(const char *a, const char *b, int curMode = 0, int curCol = 0) {
  char l0[17], l1[17];
  snprintf(l0, 17, "%-16.16s", a);
  snprintf(l1, 17, "%-16.16s", b);
  if (strcmp(l0, lastL0)) { lcd.setCursor(0, 0); lcd.print(l0); strcpy(lastL0, l0); lastCurCol = -1; }
  if (strcmp(l1, lastL1)) { lcd.setCursor(0, 1); lcd.print(l1); strcpy(lastL1, l1); lastCurCol = -1; }
  if (curMode != lastCurMode || curCol != lastCurCol) {
    lcd.noCursor();
    lcd.noBlink();
    if (curMode) {
      lcd.setCursor(curCol, 1);
      if (curMode == 1) lcd.cursor(); else lcd.blink();
    }
    lastCurMode = curMode;
    lastCurCol = curCol;
  }
}

void draw() {
  char a[40] = "", b[40] = "";
  if (popup == P_TOAST) { putLines(toastA, toastB); return; }
  if (popup == P_MSG || popup == P_SOS) {
    Msg &m = msgs[popupSlot];
    if (popup == P_SOS) snprintf(a, sizeof a, "%c SOS %.9s", G_SOS, nameOf(m.peer));
    else snprintf(a, sizeof a, "%c%s %.9s", m.kind == K_BC_IN ? G_BC : G_MAIL, m.kind == K_BC_IN ? "All" : "", nameOf(m.peer));
    snprintf(b, sizeof b, "%.16s", m.text);
    putLines(a, b);
    return;
  }
  if (popup == P_REQ) {
    snprintf(a, sizeof a, "%cReq: %s", G_FRIEND, nameOf(popupId));
    putLines(a, "D:Yes *:No C:Bk");
    return;
  }
  switch (screen) {
    case SC_HOME: {
      int u = unreadCount(), p = pendingCount();
      snprintf(a, sizeof a, "%-9.9s", myName);
      int l = strlen(a);
      if (u) l += snprintf(a + l, sizeof a - l, "%c%d", G_MAIL, u);
      if (p) snprintf(a + l, sizeof a - l, "%c%d", G_WAIT, p);
      int tmp[NODE_MAX];
      snprintf(b, sizeof b, "D:Menu  Nb%d N%d", neighborCount(), nearbyList(tmp));
      putLines(a, b);
      return;
    }
    case SC_EDIT: {
      const char *modes[] = {"abc", "Abc", "ABC", "123"};
      snprintf(a, sizeof a, "%-8.8s %s %3d", edTitle, modes[edMode], edMax - edLen);
      int start = edLen > 15 ? edLen - 15 : 0;
      snprintf(b, sizeof b, "%s", edBuf + start);
      int col = edLen - start;
      if (edPendKey) putLines(a, b, 2, col - 1);
      else putLines(a, b, 1, col);
      return;
    }
    case SC_READ: {
      char lines[12][17];
      int n = readLines(lines, 12);
      if (scroll > n - 2) scroll = max(0, n - 2);
      putLines(lines[scroll], scroll + 1 < n ? lines[scroll + 1] : "");
      return;
    }
    case SC_REQ_ACT:
      snprintf(a, sizeof a, "Accept %s?", nameOf(reqActId));
      putLines(a, "D:Yes *:No C:Bk");
      return;
    case SC_SOS:
      putLines("Send SOS to ALL?", "Hold D  C:Cancel");
      return;
    case SC_INFO: {
      char lines[7][17];
      int tmp[NODE_MAX];
      snprintf(lines[0], 17, "ID: %04X", myId);
      snprintf(lines[1], 17, "Name: %s", myName);
      snprintf(lines[2], 17, "Up: %lum", (unsigned long)(millis() / 60000));
      snprintf(lines[3], 17, "Relayed: %lu", (unsigned long)relayCount);
      snprintf(lines[4], 17, "Neighbors: %d", neighborCount());
      snprintf(lines[5], 17, "Nodes: %d", nearbyList(tmp));
      snprintf(lines[6], 17, "Pending: %d", pendingCount());
      if (scroll > 5) scroll = 5;
      putLines(lines[scroll], lines[scroll + 1]);
      return;
    }
    default: {  // list screens
      int n = listCount();
      if (!n) {
        const char *empty = screen == SC_PICK || screen == SC_FRIENDS ? "No friends yet" : screen == SC_NEARBY ? "No nodes found" : "(empty)";
        putLines(empty, "C:Back");
        return;
      }
      if (sel >= n) sel = n - 1;
      char it[16];
      listItem(sel, it);
      snprintf(a, sizeof a, ">%s", it);
      if (sel + 1 < n) { listItem(sel + 1, it); snprintf(b, sizeof b, " %s", it); }
      putLines(a, b);
    }
  }
}

void sendFromEditor() {
  if (edTarget == ED_NAME) {
    if (!edLen) return;
    strlcpy(myName, edBuf, sizeof myName);
    prefs.putString("name", myName);
    nextHello = millis();  // announce new name right away
    toast("Name saved", myName);
    go(SC_SETTINGS);
    return;
  }
  if (!edLen) return;
  if (edTarget == ED_BCAST) {
    uint16_t id = nextMsgId++;
    sendData(T_BCAST, BCAST, id, (const uint8_t *)edBuf, edLen);
    addMsg(BCAST, id, K_BC_OUT, ST_SENT, edBuf);
    toast("Broadcast sent");
  } else {
    uint16_t id;
    if (!queueOut(T_MSG, edDst, edBuf, &id)) { toast("Outbox full", "Try again later"); return; }
    addMsg(edDst, id, K_OUT, ST_PENDING, edBuf);
    toast("Sending to", nameOf(edDst));
  }
  edLen = 0;
  edBuf[0] = 0;
  go(SC_HOME);
}

const char *tapSet(char k) {
  switch (k) {
    case '1': return ".,?!'-@:1";
    case '2': return "abc2"; case '3': return "def3"; case '4': return "ghi4";
    case '5': return "jkl5"; case '6': return "mno6"; case '7': return "pqrs7";
    case '8': return "tuv8"; case '9': return "wxyz9"; case '0': return " 0";
  }
  return "";
}

void edCommit() {
  if (!edPendKey) return;
  edPendKey = 0;
  if (edMode == 1 && isalpha(edBuf[edLen - 1])) edMode = 0;  // "Abc": capitalise one letter
  dirty = true;
}

void editorKey(char k, int ev) {
  uint32_t now = millis();
  if (k >= '0' && k <= '9') {
    if (ev == EV_HOLD) {  // held: replace the letter just typed with the digit
      if (edPendKey == k && edLen) { edBuf[edLen - 1] = k; edPendKey = 0; }
      dirty = true;
      return;
    }
    if (edMode == 3) {
      if (edLen < edMax) edBuf[edLen++] = k;
    } else if (edPendKey == k && now - edPendAt < MULTITAP_MS) {
      const char *s = tapSet(k);
      edPendIdx = (edPendIdx + 1) % strlen(s);
      char c = s[edPendIdx];
      edBuf[edLen - 1] = edMode ? toupper(c) : c;
      edPendAt = now;
    } else {
      edCommit();
      if (edLen >= edMax) { toast("Message full"); return; }
      char c = tapSet(k)[0];
      edBuf[edLen++] = edMode ? toupper(c) : c;
      edPendKey = k;
      edPendIdx = 0;
      edPendAt = now;
    }
    edBuf[edLen] = 0;
  } else if (k == '*' || k == 'A') {  // backspace; hold * clears all, hold A repeats (pollKeys)
    if (ev == EV_HOLD) { if (k == '*') edLen = 0; }
    else if (edLen) edLen--;
    edBuf[edLen] = 0;
    edPendKey = 0;
  } else if (k == '#') {
    if (ev == EV_TAP) { edCommit(); edMode = (edMode + 1) % 4; }
  } else if (k == 'D' && ev == EV_TAP) {
    edCommit();
    sendFromEditor();
  } else if (k == 'C' && ev == EV_TAP) {
    edPendKey = 0;
    go(edTarget == ED_NAME ? SC_SETTINGS : SC_HOME);
  }
  dirty = true;
}

void openMenuItem(int i) {
  switch (i) {
    case 0: go(SC_INBOX); break;
    case 1: go(SC_PICK); break;
    case 2: openEditor(ED_BCAST, BCAST, "To:All"); break;
    case 3: go(SC_FRIENDS); break;
    case 4: go(SC_NEARBY); break;
    case 5: go(SC_REQS); break;
    case 6: go(SC_SOS); break;
    case 7: go(SC_SETTINGS); break;
  }
}

void answerReq(uint16_t id, bool yes) {
  for (int i = 0; i < nReqs; i++) if (reqs[i].id == id) { reqs[i] = reqs[--nReqs]; break; }
  queueOut(yes ? T_FACC : T_FREJ, id, myName, nullptr);
  if (yes) { addFriend(id, nameOf(id)); toast("Now friends:", nameOf(id)); }
  else toast("Request declined");
}

void openRead(int slot, Screen back) {
  readSlot = slot;
  readBack = back;
  if (msgs[slot].status == ST_UNREAD) msgs[slot].status = ST_READ;
  go(SC_READ);
}

void selectItem() {
  int n = listCount();
  if (!n) return;
  switch (screen) {
    case SC_MENU: openMenuItem(sel); break;
    case SC_INBOX: openRead(slotOf(sel), SC_INBOX); break;
    case SC_PICK: openEditor(ED_MSG, friends[sel].id, (String("To:") + friends[sel].name).c_str()); break;
    case SC_FRIENDS: actIdx = sel; go(SC_FRIEND_ACT); break;
    case SC_FRIEND_ACT:
      if (actIdx >= nFriends) { go(SC_FRIENDS); break; }
      if (sel == 0) openEditor(ED_MSG, friends[actIdx].id, (String("To:") + friends[actIdx].name).c_str());
      else { toast("Removed", friends[actIdx].name); removeFriend(actIdx); go(SC_FRIENDS); }
      break;
    case SC_NEARBY: {
      int idx[NODE_MAX];
      nearbyList(idx);
      uint16_t id = nodes[idx[sel]].id;
      if (friendIdx(id) >= 0) { openEditor(ED_MSG, id, (String("To:") + nameOf(id)).c_str()); break; }
      for (int i = 0; i < nReqs; i++) if (reqs[i].id == id) { reqActId = id; go(SC_REQ_ACT); return; }
      bool already = false;
      for (int i = 0; i < nReqOut; i++) already |= reqOut[i] == id;
      if (!already && nReqOut < REQ_MAX) reqOut[nReqOut++] = id;
      if (!already && !queueOut(T_FREQ, id, myName, nullptr)) { toast("Outbox full"); break; }
      toast(already ? "Already asked" : "Asking...", nameOf(id));
      break;
    }
    case SC_REQS: reqActId = reqs[sel].id; go(SC_REQ_ACT); break;
    case SC_SETTINGS:
      if (sel == 0) openEditor(ED_NAME, 0, "Name:");
      else if (sel == 1) go(SC_INFO);
      else { msgN = 0; msgHead = 0; toast("Inbox cleared"); }
      break;
    default: break;
  }
}

void onKey(char k, int ev) {
  dirty = true;
  // popups take the key first
  if (popup == P_TOAST) { popup = P_NONE; if (ev == EV_TAP) return; }
  if (popup == P_MSG || popup == P_SOS) {
    if (ev != EV_TAP) return;
    int slot = popupSlot;
    popup = P_NONE;
    if (k == 'D') openRead(slot, screen == SC_EDIT ? SC_HOME : screen);
    else if (msgs[slot].kind == K_SOS_IN) msgs[slot].status = ST_READ;  // SOS acknowledged
    return;
  }
  if (popup == P_REQ) {
    if (ev != EV_TAP) return;
    if (k == 'D' || k == '*') { popup = P_NONE; answerReq(popupId, k == 'D'); }
    else if (k == 'C') popup = P_NONE;
    return;
  }

  if (screen == SC_EDIT) { editorKey(k, ev); return; }

  if (screen == SC_SOS) {
    if (k == 'D' && ev == EV_HOLD) {
      char text[MAX_TXT + 1];
      snprintf(text, sizeof text, "SOS from %s! Need help.", myName);
      uint16_t id = nextMsgId++;
      sendData(T_SOS, BCAST, id, (const uint8_t *)text, strlen(text));
      addMsg(BCAST, id, K_SOS_OUT, ST_SENT, text);
      go(SC_HOME);
      toast("SOS SENT", "to all nodes");
    } else if (k == 'C' && ev == EV_TAP) go(SC_HOME);
    return;
  }

  if (screen == SC_REQ_ACT) {
    if (ev != EV_TAP) return;
    if (k == 'D' || k == '*') { answerReq(reqActId, k == 'D'); go(SC_REQS); }
    else if (k == 'C') go(SC_REQS);
    return;
  }

  if (ev != EV_TAP) return;

  if (screen == SC_HOME) {
    if (k == 'D') go(SC_MENU);
    else if (k >= '1' && k <= '0' + MENU_N) openMenuItem(k - '1');
    else if (k == 'A') go(SC_INBOX);
    else if (k == 'B') go(SC_NEARBY);
    return;
  }

  if (screen == SC_READ || screen == SC_INFO) {
    if (k == 'A' && scroll > 0) scroll--;
    else if (k == 'B') scroll++;  // clamped in draw()
    else if (k == 'C') go(screen == SC_READ ? readBack : SC_SETTINGS);
    else if (k == 'D' && screen == SC_READ) {
      Msg &m = msgs[readSlot];
      if (m.kind == K_IN && friendIdx(m.peer) >= 0) openEditor(ED_MSG, m.peer, (String("To:") + nameOf(m.peer)).c_str());
    }
    return;
  }

  // list screens
  int n = listCount();
  if (k == 'A') sel = n ? (sel - 1 + n) % n : 0;
  else if (k == 'B') sel = n ? (sel + 1) % n : 0;
  else if (k == 'D') selectItem();
  else if (k == 'C') {
    Screen back = SC_MENU;
    if (screen == SC_MENU) back = SC_HOME;
    else if (screen == SC_FRIEND_ACT) back = SC_FRIENDS;
    go(back);
  } else if (screen == SC_MENU && k >= '1' && k <= '0' + MENU_N) openMenuItem(k - '1');
}

// Key polling: tap registers after TAP_MS held; holding longer fires EV_HOLD once; A/B auto-repeat.
void pollKeys() {
  static char down = NO_KEY;
  static uint32_t downAt, lastRep;
  static bool tapped, held, swallow;
  keypad.getKeys();
  char k = NO_KEY;
  for (int i = 0; i < LIST_MAX; i++)
    if (keypad.key[i].kstate == PRESSED || keypad.key[i].kstate == HOLD) { k = keypad.key[i].kchar; break; }
  uint32_t now = millis();
  if (k != down) {
    down = k;
    downAt = now;
    tapped = held = false;
    swallow = k != NO_KEY && !lightOn && popup != P_SOS;  // first press only wakes the screen
    if (k != NO_KEY) wake();
    return;
  }
  if (k == NO_KEY || swallow) return;
  lastInput = now;
  uint32_t d = now - downAt;
  if (!tapped && d >= TAP_MS) { tapped = true; lastRep = now; onKey(k, EV_TAP); }
  else if (tapped && !held && d >= DIGIT_HOLD_MS) { held = true; onKey(k, EV_HOLD); }
  if (tapped && (k == 'A' || k == 'B') && d >= 600 && now - lastRep >= REPEAT_MS) { lastRep = now; onKey(k, EV_TAP); }
}

void uiService() {
  uint32_t now = millis();
  if (popup != P_NONE && (int32_t)(now - popupUntil) >= 0) { popup = P_NONE; dirty = true; }
  if (edPendKey && now - edPendAt >= MULTITAP_MS) edCommit();
  bool buzz = popup == P_SOS ? (now / 400) % 2 : (int32_t)(now - buzzUntil) < 0;  // SOS: in step with the flashing
  digitalWrite(BUZZER_PIN, buzz);
  if (popup == P_SOS) {  // flash backlight until acknowledged
    bool on = (now / 400) % 2;
    if (on != lightOn) { on ? lcd.backlight() : lcd.noBacklight(); lightOn = on; }
    lastInput = now;
  } else if (lightOn && now - lastInput > BACKLIGHT_MS) {
    lcd.noBacklight();
    lightOn = false;
  } else if (!lightOn && now - lastInput <= BACKLIGHT_MS) {
    lcd.backlight();
    lightOn = true;
  }
  static uint32_t lastTick;
  if (now - lastTick > 1000) { lastTick = now; if (screen == SC_HOME || screen == SC_NEARBY || screen == SC_INFO) dirty = true; }
  if (dirty) { dirty = false; draw(); }
}

// ================= setup / loop =================
void setup() {
  Serial.begin(115200);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  Wire.begin(I2C_SDA, I2C_SCL);
  lcd.init();
  lcd.backlight();
  for (int i = 0; i < 7; i++) lcd.createChar(i + 1, glyphs[i]);
  lcd.setCursor(0, 0);
  lcd.print("MeshTalk");
  lcd.setCursor(0, 1);
  lcd.print("Starting...");

  uint64_t mac = ESP.getEfuseMac();
  myId = (uint16_t)(mac >> 32);
  if (myId == 0 || myId == BCAST) myId = 0x0101;
  randomSeed(esp_random());
  nextMsgId = esp_random();

  prefs.begin("meshtalk");
  String nm = prefs.getString("name", "");
  if (nm.length()) strlcpy(myName, nm.c_str(), sizeof myName);
  else snprintf(myName, sizeof myName, "ESP-%04X", myId);
  nFriends = prefs.getBytesLength("friends") / sizeof(Friend);
  if (nFriends > FRIEND_MAX) nFriends = 0;
  if (nFriends) prefs.getBytes("friends", friends, sizeof(Friend) * nFriends);

  if (!bleInit()) {
    lcd.clear();
    lcd.print("BLE init failed");
    lcd.setCursor(0, 1);
    lcd.print("Check partition");
    Serial.println("BLE init failed");
    while (true) delay(1000);
  }
  Serial.printf("MeshTalk node %04X \"%s\" ready\n", myId, myName);
  nextHello = millis() + random(500, 2000);
  nextNbrs = millis() + 10000;
  lastInput = millis();
  lcd.clear();
  lastL0[0] = lastL1[0] = 1;  // force first draw
  dirty = true;
}

void loop() {
  RawPkt r;
  while (xQueueReceive(rxQueue, &r, 0) == pdTRUE) handlePacket(r);
  pollKeys();
  beaconService();
  outboxService();
  txService();
  uiService();
  delay(5);
}
