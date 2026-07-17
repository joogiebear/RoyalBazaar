# Bazaar pricing tools

Generates and audits `categories/*.yml` from an EcoItems + EcoShop catalogue, so a few hundred
items can be priced consistently instead of by hand. Written for the RoyalMC server; the pricing
model is general, the curated tables are not.

These are **offline authoring tools**, not part of the plugin. Nothing here ships in the jar.

## The pricing model

```
unit(X)                 = fair coins per single vanilla unit of material X
base_price(enchanted_X) = compression-multiplier x unit(X)
```

`unit` is anchored to EcoShop wherever the NPC trades that material:

```
unit = 1.5 x NPC_sell
```

That constant isn't arbitrary — it reproduces the hand-authored wheat/carrot/potato prices
exactly, so it matches what a human already judged to be fair. Materials with no NPC entry fall
back to `CURATED` in `gen_bazaar.py`, which is built by crafting identity where a recipe relates
two items (a `coal_block` is exactly 9 x `coal`) and judged by tier otherwise.

## Why the ceilings are per-item

A player can always buy material X from the NPC at `NPC_buy`. If the bazaar ever pays more than
that for the equivalent goods, compressing NPC-bought materials becomes an unbounded money
printer. So each item's ceiling solves:

```
base_price x ceiling_pct x (1 - spread)  <=  multiplier x NPC_buy(X)
```

`base_price` scales with the multiplier, so the constraint reduces to a **per-material**
`ceiling_pct` that protects the raw and its enchanted form identically. The solved value takes a
2% haircut (`SAFETY`) and rounds **down** — rounding up lands a hair over the line and hands back
a small but real loop.

## Usage

Extract the catalogue from a running server, then generate and audit:

```bash
# 1. EcoItems catalogue -> eco_prices.csv   (cat|id|buy|sell|base-item|multiplier|rarity)
#    EcoShop prices     -> ecoshop_prices.csv (shop|id|item|buy|sell)
#    (see the extraction one-liners in the project notes; both are plain CSV)

python gen_bazaar.py        # writes out/categories/*.yml
python verify_bazaar.py     # audits for money printers -- must print ALL CRITICAL CHECKS PASS
```

`verify_bazaar.py` is the important one. It evaluates every path from coins back to coins at the
**worst case** (bazaar selling at its ceiling, buying at its floor):

1. NPC -> craft -> bazaar — the one that actually bites. Must pass.
2. bazaar -> craft -> bazaar — informational; bounded by (1).
3. bazaar -> NPC — buy cheap on the bazaar, dump on the NPC. Must pass.
4. floor vs NPC — informational; the NPC is the floor by design.

**Run it after any price edit.** It has caught real loops, including a `+2,560/craft`
quartz printer.

## repricer.py

Rewrites EcoItems' `shop-pricing` block *and the lore players actually read* to agree with the
generated bazaar values. Runs server-side, in place, with a `.bak-reprice` per file.

`shop-pricing` is inert metadata — nothing in the eco suite reads it — but its numbers are
duplicated into each item's lore, so leaving them stale makes the tooltip contradict the bazaar.
