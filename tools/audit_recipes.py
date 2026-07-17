"""Audit EcoShop's NPC prices against the REAL vanilla recipe graph for money printers.

The earlier pass hardcoded a handful of recipes by hand and found two printers. This walks the
authoritative data instead: every crafting/smelting recipe shipped in the server jar
(data/minecraft/recipe/*.json), cross-referenced with every priced EcoShop item.

A printer is any recipe where you can buy all the ingredients from an NPC and sell the result
back to an NPC for more than they cost. That's unbounded and instant, so it ends an economy.

Reports, in order:
  1. CRAFT PRINTERS  -- buy ingredients, craft, sell result. The fatal ones.
  2. SMELT PRINTERS  -- same, via furnace (fuel ignored, so this over-reports slightly).
  3. UNCRAFT LEAKS   -- buy the product, break it down, sell the parts.
"""
import json, glob, os, sys
from collections import defaultdict

RECIPE_DIR = '/tmp/rx/data/minecraft/recipe'
TAG_DIR = '/tmp/rx/data/minecraft/tags/item'
SHOP_DIR = '/home/container-8380d453/plugins/EcoShop/categories'

# ---------------------------------------------------------------- EcoShop prices
def load_shop():
    import re
    prices = {}
    for root, _, files in os.walk(SHOP_DIR):
        for fn in files:
            if not fn.endswith('.yml') or fn.startswith('_'):
                continue
            txt = open(os.path.join(root, fn), encoding='utf-8').read()
            for m in re.finditer(r'^- id: (\S+)\n(.*?)(?=^- id: |\Z)', txt, re.S | re.M):
                body = m.group(2)
                item = re.search(r'^  item: (\S+)', body, re.M)
                if not item:
                    continue
                buy = re.search(r'^  buy:\n(?:.*\n)*?    value: ([\d.]+)', body, re.M)
                sell = re.search(r'^  sell:\n(?:.*\n)*?    value: ([\d.]+)', body, re.M)
                key = item.group(1).split()[0].lower()
                if ':' not in key:
                    key = 'minecraft:' + key
                prices[key] = {'buy': float(buy.group(1)) if buy else None,
                               'sell': float(sell.group(1)) if sell else None,
                               'shop': fn[:-4]}
    return prices


# ---------------------------------------------------------------- item tags
def load_tags():
    """#minecraft:planks -> {oak_planks, ...}. Recipes use tags for interchangeable inputs."""
    tags = {}
    for path in glob.glob(TAG_DIR + '/**/*.json', recursive=True):
        name = 'minecraft:' + os.path.relpath(path, TAG_DIR)[:-5].replace(os.sep, '/')
        try:
            data = json.load(open(path, encoding='utf-8'))
        except Exception:
            continue
        tags[name] = [v['id'] if isinstance(v, dict) else v for v in data.get('values', [])]

    def resolve(name, seen=None):
        seen = seen or set()
        if name in seen:
            return []
        seen.add(name)
        out = []
        for v in tags.get(name, []):
            if isinstance(v, str) and v.startswith('#'):
                out += resolve(v[1:], seen)
            else:
                out.append(v)
        return out
    return {t: resolve(t) for t in tags}


TAGS = load_tags()
SHOP = load_shop()


def ingredient_ids(ing):
    """Normalise a recipe ingredient (str | list | {item|tag}) to a list of candidate item ids."""
    if ing is None:
        return []
    if isinstance(ing, str):
        return TAGS.get(ing[1:], []) if ing.startswith('#') else [ing]
    if isinstance(ing, list):
        out = []
        for i in ing:
            out += ingredient_ids(i)
        return out
    if isinstance(ing, dict):
        if 'item' in ing:
            return ingredient_ids(ing['item'])
        if 'tag' in ing:
            return TAGS.get(ing['tag'].lstrip('#'), [])
    return []


def cheapest_buy(ids):
    """Cheapest NPC buy price among interchangeable ingredients; None if none are sold."""
    best = None
    for i in ids:
        p = SHOP.get(i)
        if p and p['buy'] is not None:
            best = p['buy'] if best is None else min(best, p['buy'])
    return best


def result_of(r):
    res = r.get('result')
    if isinstance(res, str):
        return res, 1
    if isinstance(res, dict):
        return res.get('id') or res.get('item'), res.get('count', 1)
    return None, 1


# ---------------------------------------------------------------- walk recipes
craft_loops, smelt_loops, unpriced = [], [], defaultdict(int)
checked = 0

for path in glob.glob(RECIPE_DIR + '/**/*.json', recursive=True):
    try:
        r = json.load(open(path, encoding='utf-8'))
    except Exception:
        continue
    rtype = r.get('type', '')
    rid, count = result_of(r)
    if not rid:
        continue
    out_price = SHOP.get(rid)
    if not out_price or out_price['sell'] is None:
        continue                      # NPC won't buy the result -> can't cash out -> not a printer

    # --- gather ingredients as a list of (candidate ids) ---
    inputs = []
    if rtype == 'minecraft:crafting_shaped':
        keymap = r.get('key', {})
        counts = defaultdict(int)
        for row in r.get('pattern', []):
            for ch in row:
                if ch != ' ':
                    counts[ch] += 1
        for ch, n in counts.items():
            inputs += [ingredient_ids(keymap.get(ch))] * n
    elif rtype == 'minecraft:crafting_shapeless':
        for ing in r.get('ingredients', []):
            inputs.append(ingredient_ids(ing))
    elif rtype in ('minecraft:smelting', 'minecraft:blasting', 'minecraft:smoking',
                   'minecraft:campfire_cooking'):
        inputs.append(ingredient_ids(r.get('ingredient')))
    else:
        continue                      # stonecutting/smithing/special: no fixed ingredient cost

    if not inputs:
        continue

    # every ingredient must be NPC-buyable, else the loop isn't closed
    total = 0.0
    ok = True
    for cand in inputs:
        c = cheapest_buy(cand)
        if c is None:
            ok = False
            for i in cand[:1]:
                unpriced[i] += 1
            break
        total += c
    if not ok:
        continue

    checked += 1
    revenue = out_price['sell'] * count
    if revenue > total:
        row = (revenue - total, rid, count, total, revenue, os.path.basename(path)[:-5],
               out_price['shop'])
        (smelt_loops if 'smelting' in rtype or 'blasting' in rtype or 'smoking' in rtype
         or 'campfire' in rtype else craft_loops).append(row)

    # --- uncraft leak: buy the product, break it into parts, sell the parts ---
    if len(inputs) >= 2 and out_price['buy'] is not None:
        parts = 0.0
        every = True
        for cand in inputs:
            best = None
            for i in cand:
                p = SHOP.get(i)
                if p and p['sell'] is not None:
                    best = p['sell'] if best is None else max(best, p['sell'])
            if best is None:
                every = False
                break
            parts += best
        if every and parts > out_price['buy'] * count:
            unpriced_note = os.path.basename(path)[:-5]
            smelt = parts - out_price['buy'] * count
            # only meaningful if the recipe is reversible in practice; report for a human to judge
            globals().setdefault('_leaks', []).append(
                (smelt, rid, out_price['buy'] * count, parts, unpriced_note))

print(f'EcoShop priced items : {len(SHOP)}')
print(f'vanilla recipes read : {len(glob.glob(RECIPE_DIR + "/**/*.json", recursive=True))}')
print(f'recipes fully buyable from NPCs (the only ones that can loop): {checked}')
print()

print('=' * 78)
print('1. CRAFT PRINTERS  -- buy ingredients from NPC, craft, sell result to NPC')
print('=' * 78)
if craft_loops:
    for d, rid, n, cost, rev, rec, shop in sorted(craft_loops, reverse=True):
        print(f'  +{d:10,.0f} /craft   {rid.replace("minecraft:",""):24s} x{n:<2} '
              f'cost {cost:9,.0f} -> sells {rev:9,.0f}   [{rec}]')
else:
    print('  none')

print()
print('=' * 78)
print('2. SMELT PRINTERS  -- same via furnace (fuel cost ignored, so may over-report)')
print('=' * 78)
if smelt_loops:
    for d, rid, n, cost, rev, rec, shop in sorted(smelt_loops, reverse=True):
        print(f'  +{d:10,.0f} /smelt   {rid.replace("minecraft:",""):24s} x{n:<2} '
              f'cost {cost:9,.0f} -> sells {rev:9,.0f}   [{rec}]')
else:
    print('  none')

leaks = globals().get('_leaks', [])
print()
print('=' * 78)
print('3. UNCRAFT LEAKS  -- buy product, break down, sell parts (only some are reversible)')
print('=' * 78)
if leaks:
    for d, rid, cost, parts, rec in sorted(leaks, reverse=True)[:12]:
        print(f'  +{d:10,.0f}          {rid.replace("minecraft:",""):24s} '
              f'buy {cost:9,.0f} -> parts sell {parts:9,.0f}   [{rec}]')
    print(f'  ({len(leaks)} total)')
else:
    print('  none')

print()
print(f'VERDICT: {len(craft_loops)} craft printer(s), {len(smelt_loops)} smelt printer(s)')
sys.exit(1 if craft_loops or smelt_loops else 0)
