"""Re-price EcoItems' shop-pricing block to agree with the bazaar's values.

shop-pricing is inert metadata -- nothing in the eco suite reads it. It's kept in sync purely as
documentation of what each item is worth, so the configs don't contradict the bazaar.

DO NOT write prices into item LORE. An earlier version did, and it backfired twice:
  1. eco re-injects an item's own lore into any GUI, so the baked lines appeared *inside* the
     bazaar menu on top of its live Buy/Sell lines -- while vanilla items, having no EcoItems
     config, showed only one set. Eco and vanilla entries rendered inconsistently.
  2. Lore is a static snapshot. The market floats; the text doesn't. A tooltip reading
     "~252 coins" forever is worse than no tooltip.
The bazaar menu is the single source of live prices (as on Hypixel). Flavour and recipe lore
("Crafted from 160 Cobblestone") is fine -- it never goes stale.

Runs on the server, in-place, with a .bak-reprice backup per file.
"""
import json, re, glob, os, sys

PRICES = json.load(open('/tmp/newprices.json'))
SPREAD = 0.05
ROOT = '/home/container-8380d453/plugins/EcoItems/items/enchanted_items'

changed = skipped = 0
for f in glob.glob(ROOT + '/**/*.yml', recursive=True):
    iid = os.path.basename(f)[:-4]
    if iid not in PRICES:
        skipped += 1
        continue
    base = PRICES[iid]
    buy, sell = round(base * (1 + SPREAD)), round(base * (1 - SPREAD))
    txt = open(f, encoding='utf-8').read()
    orig = txt

    # shop-pricing block only -- see the module docstring for why lore is deliberately left alone.
    txt = re.sub(r'(^shop-pricing:\n(?:.*\n)*?  buy: )[\d.]+', lambda m: m.group(1) + str(buy), txt, count=1, flags=re.M)
    txt = re.sub(r'(^shop-pricing:\n(?:.*\n)*?  sell: )[\d.]+', lambda m: m.group(1) + str(sell), txt, count=1, flags=re.M)

    if txt != orig:
        open(f + '.bak-reprice', 'w', encoding='utf-8').write(orig)
        with open(f, 'w', encoding='utf-8', newline='') as fh:
            fh.write(txt)
        changed += 1

print(f'repriced {changed} items, skipped {skipped} (not in bazaar / junk)')
