"""Re-price EcoItems' shop-pricing + lore to agree with the new bazaar values.

shop-pricing is inert metadata (nothing reads it), but its numbers are duplicated into each
item's LORE, which players DO see. Leaving them at the old generated values would have the
tooltip contradict the bazaar. Buy/sell are set to the bazaar's spread around base_price.

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

    # 1. shop-pricing block
    txt = re.sub(r'(^shop-pricing:\n(?:.*\n)*?  buy: )[\d.]+', lambda m: m.group(1) + str(buy), txt, count=1, flags=re.M)
    txt = re.sub(r'(^shop-pricing:\n(?:.*\n)*?  sell: )[\d.]+', lambda m: m.group(1) + str(sell), txt, count=1, flags=re.M)
    # 2. the lore the player actually reads
    txt = re.sub(r"(- '&7Buy Price: &6)[\d,]+( coins')", lambda m: f'{m.group(1)}{buy:,}{m.group(2)}', txt, count=1)
    txt = re.sub(r"(- '&7Sell Price: &6)[\d,]+( coins')", lambda m: f'{m.group(1)}{sell:,}{m.group(2)}', txt, count=1)

    if txt != orig:
        open(f + '.bak-reprice', 'w', encoding='utf-8').write(orig)
        with open(f, 'w', encoding='utf-8', newline='') as fh:
            fh.write(txt)
        changed += 1

print(f'repriced {changed} items, skipped {skipped} (not in bazaar / junk)')
