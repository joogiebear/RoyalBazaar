## 2026.39.1 — 2026-09-24

### 🐛 Fixes
- enforce royalbazaar.use and accept bare item ids (`0529074`)
- keep the live market across reload and derive 24h change from history (`1561c66`)
- validate item tuning, bracket EcoShop prices and cap order size (`30de0c4`)
- vanilla listings only take plain items (`f9ec225`)
- lock menus by inventory holder and keep the sign prompt off real blocks (`0f34287`)
- end prompts whose answer can never arrive (`e1b4359`)
- keep the sign prompt off block entities and blocks already in use (`5d80de2`)

### 📝 Documentation
- add MIT license (`f30a718`)

## 2026.39.0 — 2026-09-23

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`a442e70`)

## 2026.37.0 — 2026-09-13

### 🐛 Fixes
- restore sale items when Vault rejects payment (`7a4cfb2`)
- release at 10:00 Central or later, not exactly 10:00 (`00d137d`)

## 2026.36.0 — 2026-09-06

### ✨ Features
- wire the dormant EconGuard veto (`7397dee`)
- /bazaar sellall [category] command (`6fa198e`)
- admin price tooling - set, freeze, unfreeze, reset (`0bd2f20`)
- market trends menu - top risers, fallers and most-traded (`10a780b`)
- surface the price history the bazaar was already recording (`547ed3f`)

### 🐛 Fixes
- clamp buy orders to int range before pricing (`55827d8`)

### ♻️ Refactors
- move the custom-amount prompt from chat onto a sign (`2d90773`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`c9cd2e7`)

## 2026.34.0 — 2026-08-21

### ✨ Features
- deploy combat/woods/oddities + Hypixel regroup mining/farming (`a810bdc`)

### 🐛 Fixes
- create indexes without IF NOT EXISTS on MySQL (`dd571c1`)

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

