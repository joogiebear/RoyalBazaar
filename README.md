# RoyalBazaar

A Hypixel-style **Bazaar** for Paper: a commodity market with algorithmic dynamic pricing, built for
the eco suite.

Where [RoyalAuctions](https://github.com/joogiebear/RoyalAuctions) handles *one-off* items, the
Bazaar handles the things players trade constantly — cobblestone, wheat, mob drops, enchanted
materials. Different problem, different UI.

Part of a suite with [RoyalBank](https://github.com/joogiebear/RoyalBank),
[RoyalAuctions](https://github.com/joogiebear/RoyalAuctions) and
[EconGuard](https://github.com/joogiebear/EconGuard).

---

## Contents

- [Why not an order book](#why-not-an-order-book)
- [The pricing model](#the-pricing-model)
- [Requirements](#requirements)
- [Install](#install)
- [Commands & permissions](#commands--permissions)
- [Items and pricing config](#items-and-pricing-config)
- [EcoShop anchoring](#ecoshop-anchoring)
- [Menus](#menus)
- [Storage](#storage)
- [Integrations](#integrations)
- [Building](#building)
- [Gotchas](#gotchas)

---

## Why not an order book

Real Hypixel's bazaar is an order book — players place buy and sell orders and trade against *each
other*. That needs **liquidity**. Hypixel has tens of thousands of concurrent players; your server
almost certainly doesn't. On a normal server an order book is a ghost town: nobody to trade against,
so nothing fills, and the feature is dead on arrival.

RoyalBazaar instead makes the **server the counterparty**, always, with infinite depth. There is
always a price to buy at and a price to sell at, whether 200 players are online or 2. The prices
still move with supply and demand — that's what the engine below is for.

---

## The pricing model

Each item has a **mid price**. Buying pushes it up, selling pushes it down, along an exponential
curve:

```
mid' = mid × e^(±q / E)
```

The crucial part: **cost and proceeds are the integral along that curve**, not `quantity × price`:

```
buy  cost     = E × mid × (e^( q/E) − 1) × (1 + spread/2)
sell proceeds = E × mid × (1 − e^(−q/E)) × (1 − spread/2)
```

Which gives three properties that matter:

- **Small trades feel normal.** When `q ≪ E`, `e^(q/E) ≈ 1 + q/E`, so cost ≈ `q × price`. Linear,
  unsurprising.
- **Whales price themselves out.** Dumping 100k of an item craters the price *on yourself* — each
  marginal unit sells for less than the last. You cannot drain the market at a flat price.
- **No arbitrage loop exists.** A round trip loses to the spread **and** to your own market impact:
  you buy (pushing the price up against yourself), then sell (pushing it back down). This isn't a
  rule patched on top to stop exploiters — the maths makes it impossible.

**`elasticity` (E)** is the knob that matters most per item: high = stable staple (cobblestone),
low = volatile rare (netherite).

**Mean reversion** pulls each price back toward its `base_price` every tick, in log space, so a panic
sell recovers over time instead of staying crashed:

```
mid = clamp( mid × e^((ln(base) − ln(mid)) × reversion_rate), floor, ceiling )
```

**Floor and ceiling caps** are a hard backstop — clamped after every trade *and* every tick, so no
formula edge case can send a price to 0 or ∞.

### A worked example

`base = 100`, `E = 50,000`, `spread = 5%`:

| Trade | Cost | New mid |
|---|---|---|
| Buy 64 | ~$6,565 | 100.13 — barely moves ✅ normal play |
| Buy 100,000 | ~$32.7M | 739 — the curve punishes the whale ✅ |

---

## Requirements

| | |
|---|---|
| **Server** | Paper 1.21+ (tested on Paper 26.2 / Java 25) |
| **Required** | Vault **and an economy provider** (EssentialsX, CMI, an EcoBits currency with `vault: true`, …) |
| **Optional** | eco (for `ecoitems:` items), EcoShop (for price anchoring), EconGuard, PlaceholderAPI |

Runs without the eco suite — you just lose custom items and EcoShop anchoring.

## Install

1. Drop `RoyalBazaar.jar` into `plugins/`.
2. Start the server. Configs generate on first run.
3. `/bz`.

## Commands & permissions

Base command `/bazaar`, aliases **`/bz`**, `/rbazaar`.

| Command | |
|---|---|
| `/bz` | Open the bazaar |
| `/bz price <item>` | Show an item's live buy / sell / mid |
| `/bz reload` | **Admin.** Reload config, categories, menus |

| Node | Default |
|---|---|
| `royalbazaar.use` | `true` |
| `royalbazaar.admin` | `op` |

---

## Items and pricing config

The bundled `categories/` are **vanilla-only** — 338 items across farming, mining, combat, woods &
fishes and oddities — so a fresh install works on any server without EcoItems. `oddities.yml` carries
a commented EcoItems example showing `base_price: auto` and the NPC anchors; uncomment it only if
that plugin is installed.


One file per category in `categories/`. The file name is the category id.

```yaml
# categories/mining.yml
name: "&bMining"
icon: "minecraft:iron_pickaxe"
slot: 13                  # where this category sits in the icon grid

defaults:                 # every item inherits these unless it overrides them
  spread: 0.05            # 5% gap between buy and sell — your margin + inflation sink
  elasticity: 40000       # units to move the price by a factor of e (higher = more stable)
  reversion_rate: 0.02    # fraction pulled back toward base each tick
  floor_pct: 0.4          # price never below 40% of base
  ceiling_pct: 3.0        # price never above 300% of base

items:
  cobblestone:
    item: "minecraft:cobblestone"
    base_price: 2.0
    elasticity: 100000    # staple — very stable

  diamond:
    item: "minecraft:diamond"
    base_price: 100.0
    elasticity: 20000     # rarer — more volatile
    ceiling_pct: 5.0

  enchanted_cobblestone:
    item: "ecoitems:enchanted_cobblestone"     # note: ecoitems: is PLURAL
    base_price: 320.0
```

Adding an item is one block. Removing it is deleting the block. The `defaults:` block is what stops
300 items from repeating the same five tuning lines.

### The spread is your inflation sink

Buy price is `mid × (1 + spread/2)`, sell price is `mid × (1 − spread/2)`. Every round trip through
the bazaar destroys a little money. Without a spread the bazaar leaks value and gets arbitraged.

---

### Fixing an item to a slot

Items and groups flow into the grid in config order. Give one a `row`/`column` (both 1-indexed) — or
a raw `slot:` — to pin it in place; everything unpinned fills in around it:

```yaml
wheat:
  item: "minecraft:wheat"
  base_price: 3.0
  row: 2
  column: 3
```

Pinned entries hold their square on the first page. Where the grid sits at all is the mask pattern in
the menu file, so the whole layout is config.

## EcoShop anchoring

If you run **EcoShop**, you have a problem: two places that price the same item. If both float
independently, players just arbitrage between them — which is strictly worse than having one market.

**The rule: one dynamic market per item.** Keep EcoShop's dynamic pricing **off** so it stays a fixed
NPC bracket, and let the Bazaar be the single floating market. Then anchor to it:

```yaml
  iron_block:
    item: "minecraft:iron_block"
    base_price: auto      # anchor = EcoShop's buy value  (the item's canonical price)
    npc_floor: true       # floor   = EcoShop's sell value (the NPC never undercuts the bazaar)
    npc_ceiling: true     # ceiling = EcoShop's buy value
```

RoyalBazaar reads `plugins/EcoShop/categories/*.yml` directly — read-only, no API coupling. EcoShop
brackets each item with a `buy.value` (what a player pays the NPC — the natural ceiling) and a
`sell.value` (what the NPC pays the player — the natural floor). The bazaar floats **between** them.

Each option falls back to its `*_pct` default if EcoShop is absent or doesn't list the item, so
mixing anchored and explicit items in one category is fine.

> **Tuning note:** anchoring `base_price` to EcoShop's *buy* value puts the item at the **top** of its
> range, and with `npc_ceiling: true` the mid can then only fall, never rise. If you want headroom,
> either drop `npc_ceiling` or anchor the base nearer the midpoint of EcoShop's buy/sell.

---

## Menus

`/bazaar` opens a category directly — there is no separate landing menu. A **category rail** down the
left of the category and group menus moves between categories, drawn from each category's own `icon`
and `name`, so adding a category adds a rail entry with no menu editing:

```yaml
category-rail:
  enabled: true
  column: 1
  rows: [1, 2, 3, 4, 5]
  glint-selected: true
```

Which category `/bazaar` opens is `default-category` in `config.yml`; leave it empty and the first
configured category is used.

The menus are:

| File | Purpose |
|---|---|
| `bazaar_category.yml` | A category's grid — group icons, or products for a flat category |
| `bazaar_group.yml` | One group's products |
| `bazaar_product.yml` | A single item: live stats, plus Buy Instantly / Sell Instantly |
| `bazaar_buy.yml` | Quantity picker — 1, a stack, fill inventory, or a custom amount typed on a sign |

All in the **EcoMenus dialect** — mask/pattern, `slots:` with `location: {row, column}`,
inline item specs, `left-click:` effect lists, `%percent%` placeholders.

One extension: a **`content:` region**. The `0` slots in the mask are auto-populated with the
category's items and paged, so **adding an item never means touching a menu file**:

```yaml
mask:
  items:
    - black_stained_glass_pane
  pattern:
    - "111111111"
    - "100000001"        # 0 = live product slots, auto-filled and paged
    - "111111111"

content:
  template:
    item: "%rbazaar_item%"
    name: "%rbazaar_item_display%"
    lore:
      - "&7Buy:  &a$%rbazaar_buy_price%"
      - "&7Sell: &e$%rbazaar_sell_price%"
      - "&7Trend: %rbazaar_trend% &7(%rbazaar_change_24h% 24h)"
    left-click:
      - id: rbazaar_open_product
        args:
          item: "%rbazaar_item%"
```

**Effect ids:** `rbazaar_open_product` `rbazaar_open_buy` `rbazaar_buy` `rbazaar_sell`
`rbazaar_sell_all` `rbazaar_search` `rbazaar_buy_prompt` `rbazaar_sell_prompt`
`rbazaar_buy_amount_prompt` `rbazaar_back` `rbazaar_next_page` `rbazaar_prev_page` `open_menu`
`close` / `close_inventory` `play_sound` `send_message`

`rbazaar_buy` / `rbazaar_sell` take an `amount:` arg — a number, `all`, or `fill` (buy as many as
fit in the inventory, capped by what the player can afford).

`rbazaar_sell_all` scopes itself to how deep the player has navigated. Anywhere above a group it
sells everything the bazaar trades, because a player standing in the bazaar wants one button that
empties their bags; inside a group it narrows to that group's items. A `scope: all` arg forces the
wide behaviour even inside a group.

`rbazaar_back` works the parent out from the open menu rather than naming a target, so the same
button behaves correctly however the player arrived.

**Placeholders:** `%rbazaar_item%` `%rbazaar_item_display%` `%rbazaar_buy_price%`
`%rbazaar_sell_price%` `%rbazaar_buy_cost_1%` `%rbazaar_buy_cost_64%` `%rbazaar_sell_value_1%`
`%rbazaar_sell_value_64%` `%rbazaar_sell_value_all%` `%rbazaar_held_amount%` `%rbazaar_trend%`
`%rbazaar_change_24h%` `%rbazaar_volume_24h%` `%rbazaar_spread_pct%`
`%rbazaar_category%` `%rbazaar_category_id%` `%rbazaar_group%` `%rbazaar_group_name%`

---

## Storage

```yaml
storage:
  type: SQLITE            # or MYSQL for a network
  sqlite-file: bazaar.db
```

Tables: `rb_state` (current prices), `rb_history` (snapshots for graphs / 24h change),
`rb_transactions` (audit log).

**Prices are authoritative in memory**, flushed write-behind on a timer and on shutdown — a trade
never touches disk on the hot path. Trades run on the main thread, so the price mutation, balance
move and inventory change are effectively atomic: no partial trades, no race between two buyers.

```yaml
engine:
  tick-interval-seconds: 60      # mean reversion + stat roll
  history-interval-seconds: 300  # price snapshot for graphs
  flush-interval-seconds: 30     # write-behind flush
```

---

## Integrations

- **eco** — items are eco lookup ids (`ecoitems:enchanted_cobblestone`). eco resolves *and matches*
  them, including custom-item NBT, so selling a custom item back works correctly. Vanilla ids use
  Bukkit directly.
- **EcoShop** — price anchoring (see above). Read-only file parse.
- **EconGuard** — a **pre-trade veto** seam exists: every trade is offered to EconGuard before any
  money or items move (rate limits, circuit breakers, wash-trade heuristics), plus a post-trade
  audit feed. *Currently permissive — the real API call is a TODO.*
- **PlaceholderAPI** — `%royalbazaar_buy_<itemId>%`, `%royalbazaar_sell_<itemId>%`,
  `%royalbazaar_mid_<itemId>%`. Item ids keep their namespace.

---

## Building

```bash
mvn -DskipTests package     # -> target/RoyalBazaar.jar
```

Java 21, Maven. Versioning is `year.week.revision` (e.g. `2026.28.0`), matching the eco suite.

---

## Gotchas

- **Paper 26.2 requires Java 25** to run (the plugin targets Java 21 bytecode).
- **EcoItems' namespace is `ecoitems:` (plural)**, not `ecoitem:`.
- **`base_price: auto` needs EcoShop.** If EcoShop doesn't list the item, the item is skipped with a
  warning — it does not silently get a made-up price.
- The plugin **waits** for a Vault economy provider instead of disabling when one isn't registered at
  enable time. Vault being a hard dependency says nothing about the *economy plugin*, which can
  register later — disabling would kill the plugin purely because of plugin load order.

### Upgrading

`gui/bazaar_main.yml` (the old category hub) has been **removed**. `/bazaar` now opens a category and
the rail navigates between them. If you customised that file it is no longer read and can be deleted;
any button still pointing at `menu: bazaar_main` lands on the default category rather than erroring,
so nothing needs editing first.

Category icons now come from each category's own `slot`, `icon` and `name` rather than being written
into a menu file, so a category added after an upgrade appears on its own.

### Known rough edges

- **"Sell All" has no confirmation.** One click dumps your entire stack at market. Worth gating.
- **The EconGuard veto is a permissive stub.** The seam is in place; the call isn't.
