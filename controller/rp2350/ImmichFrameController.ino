/*
 * ImmichFrame controller UI for Waveshare RP2350-Touch-LCD-2.8.
 * Uses the vendor display/touch drivers retained in controller/vendor.
 */
#include "DEV_Config.h"
#include "LCD_2in8.h"
#include "GUI_Paint.h"
#include "CST328.h"
#include "waiting_screen_rgb565.h"

// The LCD framebuffer is portrait (240x320); the controller UI is deliberately
// rotated 90 degrees clockwise into a 320x240 landscape layout.
static const uint16_t FRAMEBUFFER_W = 240;
static const uint16_t FRAMEBUFFER_H = 320;
static const uint16_t SCREEN_W = 320;
static const uint16_t SCREEN_H = 240;
static const uint16_t TOP_H = 48;
static const uint16_t CARD_Y = 55;
static const uint16_t CARD_W = 98;
static const uint16_t CARD_H = 177;
static const uint16_t CARD_X[3] = {8, 111, 214};
static const uint16_t THUMB_W = 84;
static const uint16_t THUMB_H = 103;
static const uint32_t INTERACTION_TIMEOUT_MS = 60000;

enum Screen { HOME, DETAILS, CONFIRM_SEND, SEND_STATUS, MAP, FILTER, CONFIRM_FILTER, SETTINGS };
enum SendState { SEND_IDLE, SEND_WAITING, SEND_SENT, SEND_FAILED };
struct PhotoCard {
  char filename[25];
  char details[49];
  char year[5];
  char people[41];
  char location[41];
  char assetId[40];
  uint16_t accent;
};

static UWORD *canvas;
static Screen screen = HOME;
static uint8_t selected = 0;
static bool paused = false;
static bool resumeAfterDetails = false;
static bool filterFolder = false;
static SendState sendState = SEND_IDLE;
static uint32_t sendStartedAt = 0;
static uint32_t lastInteractionAt = 0;
static bool sendScreenAbandoned = false;
static char sendError[65] = "";
static bool touchWasDown = false;
static int8_t pressedTop = -1;
static int8_t pressedAction = -1;
static char inputLine[256];
static uint16_t inputLength = 0;
static char adminRecipient[49] = "Not set";
static char provider[25] = "Android helper";
static PhotoCard cards[3] = {
  {"Waiting-1.jpg", "Waiting for frame", "----", "No people tagged", "No location recorded", "", 0x365F},
  {"Waiting-2.jpg", "Waiting for frame", "----", "No people tagged", "No location recorded", "", 0x4D51},
  {"Waiting-3.jpg", "Waiting for frame", "----", "No people tagged", "No location recorded", "", 0x6A57},
};
static PhotoCard cardStaging[3];
static PhotoCard detailCard;
static uint16_t thumbnails[3][THUMB_W * THUMB_H];
static uint16_t thumbnailStaging[3][THUMB_W * THUMB_H];
static bool thumbnailReady[3] = {false, false, false};
static bool thumbnailStagingReady[3] = {false, false, false};
static bool thumbnailBatch = false;
static bool waitingForMainScreen = true;
static bool controllerDisplayOn = true;
static uint32_t binaryBytesRemaining = 0;
static uint32_t binaryPixelOffset = 0;
static uint8_t binarySlot = 0;
static uint8_t binaryHighByte = 0;
static bool binaryHasHighByte = false;
static bsp_cst328_data_t touchData;

static bool jsonString(const char *key, char *out, size_t outSize);

static void line(uint16_t x, uint16_t y, const char *text, sFONT *font, uint16_t fg, uint16_t bg) {
  Paint_DrawString_EN(x, y, text, font, fg, bg);
}

static void sendJson(const char *name, int slot = -1) {
  Serial.print("{\"v\":1,\"type\":\"action\",\"id\":\"");
  Serial.print(millis());
  Serial.print("\",\"name\":\"");
  Serial.print(name);
  Serial.print("\",\"args\":{");
  if (slot >= 0) { Serial.print("\"slot\":"); Serial.print(slot); }
  Serial.println("}}");
}

static void sendAssetJson(const char *name, int slot, const char *assetId) {
  Serial.print("{\"v\":1,\"type\":\"action\",\"id\":\"");
  Serial.print(millis());
  Serial.print("\",\"name\":\""); Serial.print(name);
  Serial.print("\",\"args\":{\"slot\":"); Serial.print(slot);
  Serial.print(",\"asset_id\":\""); Serial.print(assetId); Serial.println("\"}}");
}

static void sendUpdateReady() {
  char requestId[32] = "update";
  jsonString("id", requestId, sizeof(requestId));
  Serial.print("{\"v\":1,\"type\":\"firmware_update_ready\",\"id\":\"");
  Serial.print(requestId);
  Serial.println("\",\"ok\":true}");
  Serial.flush();
}

static const uint16_t INK = 0xE71C;
static const uint16_t MUTED = 0x9CD3;
static const uint16_t SURFACE = 0x18E3;
static const uint16_t SURFACE_2 = 0x2124;
static const uint16_t ACCENT = 0x66FF;
static const uint16_t PRESS = 0x04B5;
static const uint16_t BORDER = 0x6B6D;
static const uint16_t MAP_READY = 0x2E86;
static const uint16_t MAP_OFF = 0x4A49;
static const uint16_t ERROR = 0xF800;

static void rounded(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint16_t r, uint16_t colour) {
  Paint_DrawRectangle(x + r, y, x + w - r, y + h, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
  Paint_DrawRectangle(x, y + r, x + w, y + h - r, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
  Paint_DrawCircle(x + r, y + r, r, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
  Paint_DrawCircle(x + w - r, y + r, r, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
  Paint_DrawCircle(x + r, y + h - r, r, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
  Paint_DrawCircle(x + w - r, y + h - r, r, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
}

static void roundedPanel(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint16_t r, uint16_t fill, uint16_t border) {
  rounded(x, y, w, h, r, border);
  rounded(x + 1, y + 1, w - 2, h - 2, r > 1 ? r - 1 : 1, fill);
}

static bool hasLocation(uint8_t slot) {
  const char *location = cards[slot].location;
  return location[0] && strstr(location, "No location") == nullptr && strcmp(location, "Unknown") != 0;
}

static bool detailHasLocation() {
  return detailCard.location[0] && strstr(detailCard.location, "No location") == nullptr && strcmp(detailCard.location, "Unknown") != 0;
}

static void shortText(const char *source, char *out, size_t outSize) {
  if (!source[0] || strstr(source, "No people")) source = "No faces";
  size_t length = min(strlen(source), outSize - 1);
  memcpy(out, source, length); out[length] = 0;
}

static void glyph(uint8_t kind, uint16_t cx, uint16_t cy, uint16_t colour) {
  if (kind == 0) { // play / pause
    if (paused) {
      Paint_DrawLine(cx - 4, cy - 8, cx + 8, cy, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
      Paint_DrawLine(cx + 8, cy, cx - 4, cy + 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
      Paint_DrawLine(cx - 4, cy + 8, cx - 4, cy - 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    } else {
      Paint_DrawLine(cx - 5, cy - 8, cx - 5, cy + 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
      Paint_DrawLine(cx + 5, cy - 8, cx + 5, cy + 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    }
  } else if (kind == 1 || kind == 2) { // previous / next
    int8_t d = kind == 1 ? -1 : 1;
    Paint_DrawLine(cx - 6 * d, cy - 8, cx + 5 * d, cy, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    Paint_DrawLine(cx + 5 * d, cy, cx - 6 * d, cy + 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  } else if (kind == 3) { // map pin
    Paint_DrawCircle(cx, cy - 3, 6, colour, DOT_PIXEL_1X1, DRAW_FILL_EMPTY);
    Paint_DrawCircle(cx, cy - 3, 2, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
    Paint_DrawLine(cx - 4, cy + 1, cx, cy + 9, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    Paint_DrawLine(cx + 4, cy + 1, cx, cy + 9, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  } else { // settings
    Paint_DrawCircle(cx, cy, 8, colour, DOT_PIXEL_1X1, DRAW_FILL_EMPTY);
    Paint_DrawCircle(cx, cy, 3, colour, DOT_PIXEL_1X1, DRAW_FILL_FULL);
    Paint_DrawLine(cx, cy - 12, cx, cy - 8, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    Paint_DrawLine(cx, cy + 8, cx, cy + 12, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    Paint_DrawLine(cx - 12, cy, cx - 8, cy, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
    Paint_DrawLine(cx + 8, cy, cx + 12, cy, colour, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  }
}

static void drawTopButton(uint8_t index, uint16_t x) {
  bool down = pressedTop == (int8_t)index;
  uint16_t bg = down ? PRESS : (index == 0 && paused ? 0x3A42 : SURFACE);
  roundedPanel(x, 7, 68, 34, 9, bg, BORDER);
  glyph(index == 3 ? 4 : index, x + 34, 24, down ? WHITE : (index == 0 && paused ? 0xFFE0 : INK));
}

static void drawPreview(uint8_t slot) {
  uint16_t x = CARD_X[slot];
  uint16_t accent = cards[slot].accent;
  roundedPanel(x, CARD_Y, CARD_W, CARD_H, 11, SURFACE, BORDER);
  roundedPanel(x + 6, CARD_Y + 6, CARD_W - 12, 105, 8, accent, BORDER);
  if (thumbnailReady[slot]) {
    for (uint16_t py = 0; py < THUMB_H; ++py) {
      for (uint16_t px = 0; px < THUMB_W; ++px) {
        Paint_DrawPoint(x + 7 + px, CARD_Y + 7 + py, thumbnails[slot][py * THUMB_W + px], DOT_PIXEL_1X1, DOT_FILL_RIGHTUP);
      }
    }
  } else {
    Paint_DrawCircle(x + 25, CARD_Y + 31, 9, 0xFFE0, DOT_PIXEL_1X1, DRAW_FILL_FULL);
    Paint_DrawLine(x + 8, CARD_Y + 102, x + 41, CARD_Y + 53, WHITE, DOT_PIXEL_1X1, LINE_STYLE_SOLID);
    Paint_DrawLine(x + 41, CARD_Y + 53, x + CARD_W - 8, CARD_Y + 102, WHITE, DOT_PIXEL_1X1, LINE_STYLE_SOLID);
  }
  roundedPanel(x + 6, CARD_Y + 117, CARD_W - 12, 53, 6, SURFACE_2, BORDER);
  line(x + 12, CARD_Y + 123, cards[slot].year, &Font16, INK, SURFACE_2);
  if (hasLocation(slot)) glyph(3, x + 75, CARD_Y + 132, MAP_READY);
  char people[15]; shortText(cards[slot].people, people, sizeof(people));
  line(x + 12, CARD_Y + 147, people, &Font8, MUTED, SURFACE_2);
}

static void drawHome() {
  Paint_Clear(0x0841);
  const uint16_t buttonX[4] = {16, 92, 168, 244};
  for (uint8_t i = 0; i < 4; ++i) drawTopButton(i, buttonX[i]);
  for (uint8_t i = 0; i < 3; ++i) drawPreview(i);
  LCD_2IN8_Display(canvas);
}

static void drawWaitingForMainScreen() {
  Paint_Clear(0x0841);
  for (uint16_t py = 0; py < 180; ++py) {
    for (uint16_t px = 0; px < SCREEN_W; ++px) {
      Paint_DrawPoint(px, py, WAITING_SCREEN_RGB565[py * SCREEN_W + px], DOT_PIXEL_1X1, DOT_FILL_RIGHTUP);
    }
  }
  roundedPanel(8, 184, 304, 48, 12, SURFACE, BORDER);
  line(50, 190, "waiting for the main", &Font16, INK, SURFACE);
  line(72, 211, "screen to arrive", &Font16, ACCENT, SURFACE);
  LCD_2IN8_Display(canvas);
}

static void drawDetails() {
  Paint_Clear(0x0841);
  line(14, 14, "PHOTO DETAILS", &Font12, MUTED, 0x0841);
  rounded(10, 34, 300, 137, 13, SURFACE);
  line(22, 48, detailCard.filename, &Font20, INK, SURFACE);
  line(22, 77, detailCard.details, &Font12, MUTED, SURFACE);
  line(22, 104, "PEOPLE", &Font8, ACCENT, SURFACE);
  line(22, 116, detailCard.people, &Font16, INK, SURFACE);
  line(22, 143, "LOCATION", &Font8, ACCENT, SURFACE);
  line(86, 140, detailCard.location, &Font12, INK, SURFACE);
  uint16_t sendBg = pressedAction == 0 ? PRESS : 0x3A42;
  bool locationReady = detailHasLocation();
  uint16_t mapBg = pressedAction == 1 ? PRESS : (locationReady ? MAP_READY : MAP_OFF);
  uint16_t filterBg = pressedAction == 2 ? PRESS : 0x42A8;
  uint16_t closeBg = pressedAction == 3 ? PRESS : 0x2A69;
  roundedPanel(8, 188, 72, 38, 9, sendBg, BORDER); line(26, 201, "SEND", &Font12, WHITE, sendBg);
  roundedPanel(86, 188, 72, 38, 9, mapBg, BORDER); line(109, 201, "MAP", &Font12, WHITE, mapBg);
  if (!locationReady) { Paint_DrawLine(94, 198, 150, 218, ERROR, DOT_PIXEL_2X2, LINE_STYLE_SOLID); Paint_DrawLine(150, 198, 94, 218, ERROR, DOT_PIXEL_2X2, LINE_STYLE_SOLID); }
  roundedPanel(164, 188, 72, 38, 9, filterBg, BORDER); line(174, 201, "FILTER", &Font12, WHITE, filterBg);
  roundedPanel(242, 188, 70, 38, 9, closeBg, BORDER); line(253, 201, "CLOSE", &Font12, WHITE, closeBg);
  LCD_2IN8_Display(canvas);
}

static void drawSendConfirmation() {
  Paint_Clear(0x0841);
  line(14, 12, "SEND THIS PHOTO?", &Font16, MUTED, 0x0841);
  rounded(10, 42, 300, 112, 13, SURFACE);
  line(24, 58, detailCard.filename, &Font16, INK, SURFACE);
  line(24, 88, "TO", &Font8, ACCENT, SURFACE);
  line(50, 84, adminRecipient, &Font16, INK, SURFACE);
  line(24, 119, "VIA", &Font8, ACCENT, SURFACE);
  line(58, 115, provider, &Font12, INK, SURFACE);
  roundedPanel(10, 184, 142, 42, 10, pressedAction == 0 ? PRESS : 0x3A42, BORDER);
  line(59, 198, "SEND", &Font16, WHITE, pressedAction == 0 ? PRESS : 0x3A42);
  roundedPanel(168, 184, 142, 42, 10, pressedAction == 2 ? PRESS : 0x2A69, BORDER);
  line(211, 198, "CANCEL", &Font16, WHITE, pressedAction == 2 ? PRESS : 0x2A69);
  LCD_2IN8_Display(canvas);
}

static void drawSendStatus() {
  Paint_Clear(0x0841);
  line(14, 12, "PHOTO MESSAGE", &Font16, MUTED, 0x0841);
  rounded(10, 42, 300, 126, 13, SURFACE);
  if (sendState == SEND_WAITING) {
    line(82, 65, "SENDING...", &Font20, 0xFFE0, SURFACE);
    line(44, 105, "Please wait", &Font16, INK, SURFACE);
    line(44, 130, "Do not press Send again", &Font12, MUTED, SURFACE);
  } else if (sendState == SEND_SENT) {
    line(67, 65, "MESSAGE SENT", &Font20, MAP_READY, SURFACE);
    line(32, 105, "Delivered to", &Font12, MUTED, SURFACE);
    line(126, 101, adminRecipient, &Font16, INK, SURFACE);
  } else {
    line(70, 65, "SEND FAILED", &Font20, ERROR, SURFACE);
    line(24, 105, sendError[0] ? sendError : "No delivery confirmation", &Font12, INK, SURFACE);
  }
  uint16_t doneBg = sendState == SEND_WAITING ? MAP_OFF : (pressedAction == 2 ? PRESS : 0x2A69);
  roundedPanel(10, 184, 300, 42, 10, doneBg, BORDER);
  line(sendState == SEND_WAITING ? 91 : 133, 198, sendState == SEND_WAITING ? "WAITING" : "DONE", &Font16, WHITE, doneBg);
  LCD_2IN8_Display(canvas);
}

static void drawFilter() {
  Paint_Clear(0x0841);
  line(14, 12, "HIDE FROM THIS FRAME", &Font12, MUTED, 0x0841);
  roundedPanel(10, 38, 300, 48, 11, pressedAction == 0 ? PRESS : SURFACE_2, BORDER);
  line(25, 53, "BLOCK THIS PHOTO", &Font16, WHITE, pressedAction == 0 ? PRESS : SURFACE_2);
  roundedPanel(10, 96, 300, 48, 11, pressedAction == 1 ? PRESS : SURFACE_2, BORDER);
  line(25, 111, "BLOCK WHOLE FOLDER", &Font16, WHITE, pressedAction == 1 ? PRESS : SURFACE_2);
  line(15, 157, "Nothing is deleted from Immich", &Font12, MUTED, 0x0841);
  roundedPanel(10, 190, 300, 36, 10, pressedAction == 2 ? PRESS : 0x2A69, BORDER);
  line(132, 201, "CANCEL", &Font16, WHITE, pressedAction == 2 ? PRESS : 0x2A69);
  LCD_2IN8_Display(canvas);
}

static void drawFilterConfirmation() {
  Paint_Clear(0x0841);
  line(14, 12, "PLEASE CONFIRM", &Font12, MUTED, 0x0841);
  rounded(10, 42, 300, 112, 13, SURFACE);
  line(27, 61, filterFolder ? "BLOCK WHOLE FOLDER?" : "BLOCK THIS PHOTO?", &Font20, INK, SURFACE);
  line(27, 99, "It will no longer appear", &Font12, MUTED, SURFACE);
  line(27, 119, "on this frame.", &Font12, MUTED, SURFACE);
  roundedPanel(10, 184, 142, 42, 10, pressedAction == 0 ? PRESS : ERROR, BORDER);
  line(62, 198, "BLOCK", &Font16, WHITE, pressedAction == 0 ? PRESS : ERROR);
  roundedPanel(168, 184, 142, 42, 10, pressedAction == 2 ? PRESS : 0x2A69, BORDER);
  line(211, 198, "CANCEL", &Font16, WHITE, pressedAction == 2 ? PRESS : 0x2A69);
  LCD_2IN8_Display(canvas);
}

static void drawMap() {
  Paint_Clear(0x0841);
  line(14, 11, "MAP CONTROLS", &Font12, MUTED, 0x0841);
  line(112, 11, detailCard.location, &Font8, MUTED, 0x0841);

  const uint16_t normal = SURFACE_2;
  roundedPanel(61, 38, 54, 48, 11, pressedAction == 0 ? PRESS : normal, BORDER);
  roundedPanel(12, 88, 54, 48, 11, pressedAction == 1 ? PRESS : normal, BORDER);
  roundedPanel(110, 88, 54, 48, 11, pressedAction == 2 ? PRESS : normal, BORDER);
  roundedPanel(61, 138, 54, 48, 11, pressedAction == 3 ? PRESS : normal, BORDER);
  Paint_DrawLine(88, 50, 76, 67, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID); Paint_DrawLine(88, 50, 100, 67, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  Paint_DrawLine(25, 112, 43, 99, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID); Paint_DrawLine(25, 112, 43, 125, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  Paint_DrawLine(151, 112, 133, 99, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID); Paint_DrawLine(151, 112, 133, 125, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID);
  Paint_DrawLine(88, 174, 76, 157, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID); Paint_DrawLine(88, 174, 100, 157, INK, DOT_PIXEL_2X2, LINE_STYLE_SOLID);

  uint16_t plusBg = pressedAction == 4 ? PRESS : 0x2945;
  uint16_t minusBg = pressedAction == 5 ? PRESS : 0x2945;
  roundedPanel(190, 44, 116, 54, 12, plusBg, BORDER);
  roundedPanel(190, 108, 116, 54, 12, minusBg, BORDER);
  line(208, 62, "+", &Font20, WHITE, plusBg); line(242, 64, "ZOOM", &Font12, INK, plusBg);
  line(210, 126, "-", &Font20, WHITE, minusBg); line(242, 128, "ZOOM", &Font12, INK, minusBg);

  uint16_t closeBg = pressedAction == 6 ? PRESS : 0x2A69;
  roundedPanel(12, 196, 294, 34, 10, closeBg, BORDER);
  line(134, 205, "CLOSE", &Font16, WHITE, closeBg);
  LCD_2IN8_Display(canvas);
}

static void drawSettings() {
  Paint_Clear(0x0841);
  line(14, 14, "SETTINGS", &Font16, MUTED, 0x0841);
  rounded(10, 38, 300, 56, 12, SURFACE);
  line(22, 49, "ADMINISTRATOR", &Font8, ACCENT, SURFACE);
  line(22, 64, adminRecipient, &Font16, INK, SURFACE);
  rounded(10, 104, 300, 56, 12, SURFACE);
  line(22, 115, "SEND METHOD", &Font8, ACCENT, SURFACE);
  line(22, 130, provider, &Font16, INK, SURFACE);
  rounded(10, 188, 140, 38, 10, pressedAction == 0 ? PRESS : 0x3A42);
  line(49, 201, "EDIT", &Font16, WHITE, pressedAction == 0 ? PRESS : 0x3A42);
  rounded(170, 188, 140, 38, 10, pressedAction == 2 ? PRESS : 0x2A69);
  line(211, 201, "CLOSE", &Font16, WHITE, pressedAction == 2 ? PRESS : 0x2A69);
  LCD_2IN8_Display(canvas);
}

static void render() {
  if (!controllerDisplayOn) return;
  if (waitingForMainScreen) { drawWaitingForMainScreen(); return; }
  if (screen == HOME) drawHome();
  else if (screen == DETAILS) drawDetails();
  else if (screen == CONFIRM_SEND) drawSendConfirmation();
  else if (screen == SEND_STATUS) drawSendStatus();
  else if (screen == MAP) drawMap();
  else if (screen == FILTER) drawFilter();
  else if (screen == CONFIRM_FILTER) drawFilterConfirmation();
  else drawSettings();
}

static bool jsonString(const char *key, char *out, size_t outSize) {
  char needle[40]; snprintf(needle, sizeof(needle), "\"%s\":\"", key);
  char *start = strstr(inputLine, needle); if (!start) return false;
  start += strlen(needle); char *end = strchr(start, '"'); if (!end) return false;
  size_t len = min((size_t)(end - start), outSize - 1); memcpy(out, start, len); out[len] = 0; return true;
}

static int jsonSlot() {
  char *slot = strstr(inputLine, "\"slot\":"); return slot ? constrain(atoi(slot + 7), 0, 2) : 0;
}

static int jsonInt(const char *key, int fallback = 0) {
  char needle[40]; snprintf(needle, sizeof(needle), "\"%s\":", key);
  char *value = strstr(inputLine, needle); return value ? atoi(value + strlen(needle)) : fallback;
}

static uint8_t hexNibble(char value) {
  if (value >= '0' && value <= '9') return value - '0';
  if (value >= 'A' && value <= 'F') return value - 'A' + 10;
  if (value >= 'a' && value <= 'f') return value - 'a' + 10;
  return 0;
}

static void handleHostMessage() {
  if (strstr(inputLine, "\"type\":\"display_power\"")) {
    bool turnOn = strstr(inputLine, "\"on\":true") != nullptr;
    if (turnOn != controllerDisplayOn) {
      controllerDisplayOn = turnOn;
      if (turnOn) {
        LCD_2IN8_SetPower(true);
        DEV_SET_PWM(85);
        render();
      } else {
        DEV_SET_PWM(0);
        LCD_2IN8_SetPower(false);
      }
    }
  } else if (strstr(inputLine, "\"type\":\"photo\"")) {
    int slot = jsonSlot();
    PhotoCard &target = thumbnailBatch ? cardStaging[slot] : cards[slot];
    jsonString("filename", target.filename, sizeof(target.filename));
    jsonString("details", target.details, sizeof(target.details));
    jsonString("year", target.year, sizeof(target.year));
    jsonString("people", target.people, sizeof(target.people));
    jsonString("location", target.location, sizeof(target.location));
    jsonString("asset_id", target.assetId, sizeof(target.assetId));
    if (!thumbnailBatch) render();
  } else if (strstr(inputLine, "\"type\":\"thumbnail_batch_begin\"")) {
    thumbnailBatch = true;
    memcpy(cardStaging, cards, sizeof(cards));
    for (uint8_t i = 0; i < 3; ++i) thumbnailStagingReady[i] = false;
  } else if (strstr(inputLine, "\"type\":\"thumbnail_begin\"")) {
    thumbnailStagingReady[jsonSlot()] = false;
  } else if (strstr(inputLine, "\"type\":\"thumbnail_binary\"")) {
    binarySlot = jsonSlot();
    binaryPixelOffset = 0;
    binaryHasHighByte = false;
    binaryBytesRemaining = constrain(jsonInt("pixels"), 0, THUMB_W * THUMB_H) * 2U;
  } else if (strstr(inputLine, "\"type\":\"thumbnail_chunk\"")) {
    int slot = jsonSlot();
    int offset = constrain(jsonInt("offset"), 0, THUMB_W * THUMB_H);
    char encoded[161] = {0};
    if (jsonString("data", encoded, sizeof(encoded))) {
      size_t pixels = strlen(encoded) / 4;
      for (size_t i = 0; i < pixels && offset + (int)i < THUMB_W * THUMB_H; ++i) {
        const char *p = encoded + i * 4;
        thumbnailStaging[slot][offset + i] = (hexNibble(p[0]) << 12) | (hexNibble(p[1]) << 8) | (hexNibble(p[2]) << 4) | hexNibble(p[3]);
      }
    }
  } else if (strstr(inputLine, "\"type\":\"thumbnail_end\"")) {
    thumbnailStagingReady[jsonSlot()] = true;
  } else if (strstr(inputLine, "\"type\":\"thumbnail_batch_end\"")) {
    for (uint8_t slot = 0; slot < 3; ++slot) {
      if (thumbnailStagingReady[slot]) {
        cards[slot] = cardStaging[slot];
        memcpy(thumbnails[slot], thumbnailStaging[slot], sizeof(thumbnails[slot]));
        thumbnailReady[slot] = true;
      }
    }
    thumbnailBatch = false;
    waitingForMainScreen = false;
    screen = HOME;
    render();
  } else if (strstr(inputLine, "\"type\":\"frame_state\"")) {
    paused = strstr(inputLine, "\"paused\":true") != nullptr; render();
  } else if (strstr(inputLine, "\"type\":\"settings\"")) {
    jsonString("admin", adminRecipient, sizeof(adminRecipient));
    jsonString("provider", provider, sizeof(provider)); render();
  } else if (strstr(inputLine, "\"type\":\"send_result\"")) {
    char status[12] = "";
    jsonString("status", status, sizeof(status));
    if (!sendScreenAbandoned) screen = SEND_STATUS;
    if (strcmp(status, "sent") == 0) {
      sendState = SEND_SENT; sendError[0] = 0;
    } else if (strcmp(status, "failed") == 0) {
      sendState = SEND_FAILED;
      if (!jsonString("error", sendError, sizeof(sendError))) strcpy(sendError, "LXMF delivery failed");
    } else sendState = SEND_WAITING;
    render();
  } else if (strstr(inputLine, "\"type\":\"firmware_update_prepare\"")) {
    // Only the local USB host can issue this. The Android helper verifies the
    // downloaded update before this request; the RP2350 ROM then owns flashing.
    if (strstr(inputLine, "\"confirm\":true")) {
      sendUpdateReady();
      delay(500);  // Let the host receive the acknowledgement before USB detaches.
      rp2040.rebootToBootloader();
    }
  }
}

static void readSerial() {
  while (Serial.available()) {
    uint8_t raw = (uint8_t)Serial.read();
    if (binaryBytesRemaining > 0) {
      --binaryBytesRemaining;
      if (!binaryHasHighByte) { binaryHighByte = raw; binaryHasHighByte = true; }
      else {
        if (binaryPixelOffset < THUMB_W * THUMB_H) thumbnailStaging[binarySlot][binaryPixelOffset++] = ((uint16_t)binaryHighByte << 8) | raw;
        binaryHasHighByte = false;
      }
      continue;
    }
    char c = (char)raw;
    if (c == '\n') { inputLine[inputLength] = 0; handleHostMessage(); inputLength = 0; }
    else if (inputLength < sizeof(inputLine) - 1 && c >= 32) inputLine[inputLength++] = c;
  }
}

static void closePhotoView() {
  screen = HOME;
  if (resumeAfterDetails) {
    resumeAfterDetails = false;
    paused = false;
    sendJson("resume");
  }
}

static void timeoutToHome() {
  if (screen == SEND_STATUS) sendScreenAbandoned = true;
  pressedTop = -1;
  pressedAction = -1;
  closePhotoView();
  render();
}

static void press(uint16_t x, uint16_t y) {
  lastInteractionAt = millis();
  if (waitingForMainScreen) return;
  if (screen == HOME) {
    if (y <= TOP_H) {
      uint8_t button = constrain((int)((x - 16) / 76), 0, 3);
      pressedTop = button; render(); delay(100); pressedTop = -1;
      if (button == 0) { paused = !paused; sendJson("toggle_paused"); }
      else if (button == 1) sendJson("previous");
      else if (button == 2) sendJson("next");
      else { screen = SETTINGS; sendJson("settings_open"); }
    } else if (y >= CARD_Y && y <= CARD_Y + CARD_H) {
      for (uint8_t i = 0; i < 3; ++i) {
        if (x >= CARD_X[i] && x <= CARD_X[i] + CARD_W) {
          selected = i;
          detailCard = cards[i];
          resumeAfterDetails = !paused;
          if (resumeAfterDetails) { paused = true; sendAssetJson("pause", selected, detailCard.assetId); }
          render(); delay(80); screen = DETAILS; break;
        }
      }
    }
  } else if (screen == DETAILS) {
    if (y >= 170) {
      uint8_t action = x < 83 ? 0 : (x < 161 ? 1 : (x < 239 ? 2 : 3));
      pressedAction = action; render(); delay(100); pressedAction = -1;
      if (action == 0) screen = CONFIRM_SEND;
      else if (action == 1) { if (detailHasLocation()) { screen = MAP; sendJson("show_map", selected); } }
      else if (action == 2) screen = FILTER;
      else closePhotoView();
    } else if (y < 34) closePhotoView();
  } else if (screen == CONFIRM_SEND) {
    if (y >= 178) {
      int8_t action = x < 160 ? 0 : 2;
      pressedAction = action; render(); delay(100); pressedAction = -1;
      if (action == 0) {
        sendState = SEND_WAITING; sendStartedAt = millis(); sendError[0] = 0;
        sendScreenAbandoned = false;
        screen = SEND_STATUS; sendAssetJson("send_photo", selected, detailCard.assetId);
      } else screen = DETAILS;
    }
  } else if (screen == SEND_STATUS) {
    if (sendState != SEND_WAITING && y >= 178) { pressedAction = 2; render(); delay(100); pressedAction = -1; sendState = SEND_IDLE; screen = DETAILS; }
  } else if (screen == MAP) {
    int8_t action = -1;
    if (y >= 196) action = 6;
    else if (x >= 190 && y >= 44 && y <= 98) action = 4;
    else if (x >= 190 && y >= 108 && y <= 162) action = 5;
    else if (x >= 61 && x <= 115 && y >= 38 && y <= 86) action = 0;
    else if (x >= 12 && x <= 66 && y >= 88 && y <= 136) action = 1;
    else if (x >= 110 && x <= 164 && y >= 88 && y <= 136) action = 2;
    else if (x >= 61 && x <= 115 && y >= 138 && y <= 186) action = 3;
    if (action >= 0) {
      pressedAction = action; render(); delay(100); pressedAction = -1;
      if (action == 0) sendJson("map_up");
      else if (action == 1) sendJson("map_left");
      else if (action == 2) sendJson("map_right");
      else if (action == 3) sendJson("map_down");
      else if (action == 4) sendJson("map_zoom_in");
      else if (action == 5) sendJson("map_zoom_out");
      else { sendJson("close_map"); closePhotoView(); }
    }
  } else if (screen == FILTER) {
    int8_t action = y >= 185 ? 2 : (y >= 91 && y <= 151 ? 1 : (y >= 34 && y < 91 ? 0 : -1));
    if (action >= 0) {
      pressedAction = action; render(); delay(100); pressedAction = -1;
      if (action == 2) screen = DETAILS;
      else { filterFolder = action == 1; screen = CONFIRM_FILTER; }
    }
  } else if (screen == CONFIRM_FILTER) {
    if (y >= 178) {
      int8_t action = x < 160 ? 0 : 2;
      pressedAction = action; render(); delay(100); pressedAction = -1;
      if (action == 0) { sendJson(filterFolder ? "block_folder" : "block_image", selected); closePhotoView(); }
      else screen = FILTER;
    }
  } else {
    if (y >= 188 && y <= 226 && x < 150) { pressedAction = 0; render(); delay(100); pressedAction = -1; sendJson("settings_edit"); }
    else if (y >= 188 && y <= 226 && x >= 170) { pressedAction = 2; render(); delay(100); pressedAction = -1; screen = HOME; }
  }
  render();
}

void setup() {
  Serial.begin(115200);
  DEV_Module_Init();
  LCD_2IN8_Init(HORIZONTAL);
  DEV_SET_PWM(85);
  canvas = (UWORD *)malloc(FRAMEBUFFER_W * FRAMEBUFFER_H * 2);
  if (!canvas) while (true) delay(1000);
  Paint_NewImage((UBYTE *)canvas, FRAMEBUFFER_W, FRAMEBUFFER_H, ROTATE_90, BLACK);
  Paint_SetScale(65); Paint_SetRotate(ROTATE_90);
  static bsp_cst328_info_t touchInfo = {1, FRAMEBUFFER_W, FRAMEBUFFER_H};
  bsp_cst328_init(&touchInfo);
  Serial.println("{\"v\":1,\"type\":\"hello\",\"board\":\"RP2350-Touch-LCD-2.8\",\"firmware\":\"controller-ui-0.7\"}");
  lastInteractionAt = millis();
  render();
}

void loop() {
  readSerial();
  if (!controllerDisplayOn) { touchWasDown = false; delay(100); return; }
  if (screen != HOME && millis() - lastInteractionAt >= INTERACTION_TIMEOUT_MS) {
    timeoutToHome();
  }
  if (sendState == SEND_WAITING && millis() - sendStartedAt > 70000) {
    sendState = SEND_FAILED; strcpy(sendError, "Delivery confirmation timed out"); render();
  }
  bsp_cst328_read();
  if (bsp_cst328_get_touch_data(&touchData)) {
    if (!touchWasDown) press(touchData.coords[0].x, touchData.coords[0].y);
    touchWasDown = true;
  } else touchWasDown = false;
  delay(20);
}

// The vendor sources are intentionally compiled in this sketch so the exact,
// checked-in Waveshare board driver stack is used without copying it again.
#include "DEV_Config.cpp"
#include "LCD_2in8.cpp"
#include "CST328.cpp"
#include "GUI_Paint.cpp"
#include "font8.cpp"
#include "font12.cpp"
#include "font16.cpp"
#include "font20.cpp"

