## 2026.32.0 — 2026-08-07

### ✨ Features
- menu-level open sounds (`fec7fa4`)
- report anonymous usage stats via bStats (`0e840dc`)
- ship vanilla-only default categories (`5081316`)
- category rail, instant buy/sell and sign search (`5506b68`)
- bound price history with a retention window (`65fd677`)

### 🐛 Fixes
- scope Sell All by depth and stop doubled menu click sounds (`81e7825`)
- stop reading and clearing live market state off-thread (`feef091`)

### 📝 Documentation
- correct stale references and remove private context (`900df45`)

## 2026.29.2 — 2026-07-17

_Maintenance release._

## 2026.29.1 — 2026-07-17

### 🐛 Fixes
- pass the Modrinth payload as a file, not inline (`a380ef0`)

## 2026.29.0 — 2026-07-17

### ✨ Features
- trade raw vanilla materials alongside compressed ones (`a8dfd79`)
- Hypixel bazaar layout - 5 categories, 63 item families (`4ee0814`)
- item-family groups inside categories (`d8fedd0`)
- player-head icons + eco-style direct row/column (`683ffc6`)
- externalize user-facing strings via MessageManager + messages.yml (`2f15334`)
- add ConfigValidator for load-time sanity checks (`1623736`)
- report bazaar trades to EconGuard; move menus resources to gui/ (`592e01a`)

### 🐛 Fixes
- don't round bazaar prices to whole coins (`421cdbe`)
- stop baking bazaar prices into item lore (`fcb1612`)
- find category files in subfolders (`b642345`)

### 📝 Documentation
- add full admin and developer README (`b42c77a`)

