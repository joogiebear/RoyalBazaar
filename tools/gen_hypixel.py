"""Emit RoyalBazaar categories in the Hypixel layout.

Pricing is unchanged from gen_bazaar.py (see its docstring and tools/README.md):
    unit(X) = 1.5 x NPC_sell where EcoShop trades X, else CURATED
    base_price = compression-multiplier x unit(X)
    ceiling_pct solved per material so the bazaar can never outbid NPC sourcing.

This module only decides *where things go*; hypixel_layout.py declares the structure.
"""
import os, math, collections, importlib.util

spec = importlib.util.spec_from_file_location('layout', 'hypixel_layout.py')
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)

SPREAD, HARD_CEILING, FLOOR_FLOOR, SAFETY = 0.05, 3.0, 0.4, 0.98

# --- price inputs (identical model to gen_bazaar.py) ---
SHOP = {}
for line in open('ecoshop_prices.csv', encoding='utf-8'):
    p = line.strip().split('|')
    if len(p) >= 5 and p[3]:
        SHOP[p[2]] = {'buy': float(p[3]), 'sell': float(p[4]) if p[4] else 0.0}
SHOP['diamond_block'] = {'buy': 1980.0, 'sell': 495.0}   # craft-parity fix applied on the server
SHOP['iron_block'] = {'buy': 270.0, 'sell': 72.0}

I, G, D, C, R = 12.0, 13.5, 82.5, 1.5, 3.0
CURATED = {
    'coal_block': 40.5, 'gold_block': 9 * G, 'lapis_block': 40.5, 'redstone_block': 9 * R,
    'emerald_block': 607.5, 'packed_ice': 27.0, 'lapis_ore': 27.0,
    'gold_nugget': G / 9, 'brick': 2.25, 'bone_meal': 1.0, 'charcoal': 4.5,
    'netherrack': 1.5, 'end_stone': 3.0, 'ice': 3.0, 'red_sand': 1.5, 'flint': 3.0,
    'clay_ball': 3.0, 'dead_bush': 1.5, 'dandelion': 1.5, 'poppy': 1.5, 'azure_bluet': 1.5,
    'lilac': 3.0, 'rose_bush': 3.0, 'large_fern': 1.5, 'lily_pad': 4.5, 'wheat_seeds': 1.5,
    'jungle_sapling': 4.5, 'brown_mushroom': 3.0, 'red_mushroom': 3.0,
    'brown_mushroom_block': 6.0, 'red_mushroom_block': 6.0, 'mycelium': 15.0, 'cobweb': 15.0,
    'feather': 3.0, 'leather': 6.0, 'ink_sac': 4.5, 'lime_dye': 3.0, 'green_dye': 4.5,
    'paper': 3.0, 'blaze_rod': 24.0, 'rabbit_foot': 30.0, 'rabbit_hide': 4.5,
    'nether_brick': 6.0, 'chiseled_quartz_block': 15.0, 'dark_prismarine': 30.0,
    'ender_eye': 42.0, 'experience_bottle': 30.0, 'nether_star': 4560.0, 'beacon': 6000.0,
    'apple': 6.0, 'golden_apple': 8 * G + 6.0, 'glistering_melon_slice': 8 * (G / 9) + 1.5,
    'egg': 3.0, 'beef': 4.5, 'porkchop': 4.5, 'chicken': 4.5, 'mutton': 4.5, 'rabbit': 6.0,
    'cooked_cod': 6.75, 'cooked_salmon': 6.75, 'cooked_porkchop': 6.75, 'cooked_mutton': 6.75,
    'baked_potato': 4.5, 'bread': 9.0, 'cookie': 3.0, 'melon': 13.5,
    'pumpkin_pie': 13.5, 'mushroom_stew': 7.5, 'bowl': 1.5, 'poisonous_potato': 1.5,
    'cake': 60.0, 'carrot_on_a_stick': 12.0,
    'bucket': 3 * I, 'milk_bucket': 3 * I, 'lava_bucket': 3 * I + 9.0,
    'map': 8.0 + R, 'filled_map': 12.0, 'note_block': 45.0, 'jukebox': 90.0,
    'redstone_lamp': 24.0 + 4 * R, 'anvil': 31 * I, 'cauldron': 7 * I,
    'brewing_stand': 24.0 + 3 * C, 'furnace': 8 * C,
    'enchanting_table': 4 * 45.0 + 2 * D + 15.0, 'bookshelf': 45.0, 'book': 15.0,
    'writable_book': 22.0, 'item_frame': 9.0, 'painting': 9.0, 'armor_stand': 9.0,
    'lead': 9.0, 'name_tag': 60.0, 'firework_rocket': 3.0, 'firework_star': 9.0,
    'glass_bottle': 1.5, 'clock': 4 * G + R, 'compass': 4 * I + R,
    # --- new items added for Hypixel parity ---
    'sugar': 4.0, 'sugar_cane': 4.0, 'white_wool': 12.0, 'string': 3.0, 'spider_eye': 4.5,
    'slimeball': 6.0, 'slime_block': 54.0, 'gravel': 1.5, 'snow_block': 6.0,
    'spruce_log': 4.5, 'tropical_fish': 12.0, 'sponge': 30.0,
}

# Raw vanilla materials the bazaar trades alongside the compressed forms.
RAW_ITEMS = {
    'wheat': 'minecraft:wheat', 'carrot': 'minecraft:carrot', 'potato': 'minecraft:potato',
    'sugar_cane': 'minecraft:sugar_cane', 'cobblestone': 'minecraft:cobblestone',
    'coal': 'minecraft:coal', 'iron_block': 'minecraft:iron_block',
    'diamond_block': 'minecraft:diamond_block',
}
RAW_UNIT = {'sugar_cane': 4.0}          # hand-set, preserved from the original farming.yml
RAW_EXTRA = {'cobblestone': '    elasticity: 100000'}
RAW_AUTO = {'iron_block', 'diamond_block'}   # anchor to EcoShop instead of a fixed base_price

# --- catalogue ---
ECO = {}
for line in open('eco_prices.csv', encoding='utf-8'):
    p = line.strip().split('|')
    if len(p) >= 7 and p[4] and p[5]:
        ECO[p[1]] = {'base': p[4], 'mult': float(p[5])}


def unit_of(base):
    if base in SHOP and SHOP[base]['sell']:
        return 1.5 * SHOP[base]['sell'], 'npc'
    if base in CURATED:
        return CURATED[base], 'curated'
    return None, None


def limits(base, unit):
    ceiling, floor = HARD_CEILING, FLOOR_FLOOR
    s = SHOP.get(base)
    if s:
        if s['buy']:
            ceiling = min(HARD_CEILING, SAFETY * s['buy'] / (unit * (1 - SPREAD)))
        if s['sell']:
            floor = max(FLOOR_FLOOR, s['sell'] / unit)
    return math.floor(ceiling * 100) / 100, math.ceil(floor * 100) / 100


def fmt(v):
    return str(int(v)) if abs(v - round(v)) < 1e-9 else f'{v:.2f}'


def emit_item(L, iid, group):
    """Append one item entry. Returns False (and logs) if it can't be priced."""
    if iid in RAW_ITEMS:                       # raw vanilla material
        base = iid
        unit = RAW_UNIT.get(iid) or unit_of(iid)[0]
        if unit is None:
            return False, f'raw {iid}: no unit value'
        L.append(f'  {iid}:')
        L.append(f'    item: "{RAW_ITEMS[iid]}"')
        if iid in RAW_AUTO:
            L += ['    base_price: auto', '    npc_floor: true', '    npc_ceiling: true',
                  f'    group: {group}']
            return True, None
        ceiling, floor = limits(base, unit)
        L.append(f'    base_price: {fmt(unit)}')
        if iid in RAW_EXTRA:
            L.append(RAW_EXTRA[iid])
        if abs(ceiling - HARD_CEILING) > 1e-9:
            L.append(f'    ceiling_pct: {fmt(ceiling)}')
        if abs(floor - FLOOR_FLOOR) > 1e-9:
            L.append(f'    floor_pct: {fmt(floor)}')
        L.append(f'    group: {group}')
        # Provenance comment, same shape as compressed items -- verify_bazaar.py keys on it,
        # so without this the raws would silently skip the money-printer audit.
        L.append(f'    # 1x {base} @ {fmt(unit)}/unit (raw)')
        return True, None

    meta = ECO.get(iid)
    if not meta:
        return False, f'{iid}: not in the EcoItems catalogue'
    unit, src = unit_of(meta['base'])
    if unit is None:
        return False, f'{iid}: no unit value for base {meta["base"]!r}'
    price = meta['mult'] * unit
    ceiling, floor = limits(meta['base'], unit)
    L.append(f'  {iid}:')
    L.append(f'    item: "ecoitems:{iid}"')
    L.append(f'    base_price: {fmt(price)}')
    if abs(ceiling - HARD_CEILING) > 1e-9:
        L.append(f'    ceiling_pct: {fmt(ceiling)}')
    if abs(floor - FLOOR_FLOOR) > 1e-9:
        L.append(f'    floor_pct: {fmt(floor)}')
    L.append(f'    group: {group}')
    L.append(f'    # {fmt(meta["mult"])}x {meta["base"]} @ {fmt(unit)}/unit ({src})')
    return True, None


os.makedirs('out/categories', exist_ok=True)
for f in os.listdir('out/categories'):
    os.remove(f'out/categories/{f}')                     # 9 old categories -> 5 new ones

placed, problems, report = set(), [], []
for cat_id, (name, icon, (row, col), groups) in layout.LAYOUT.items():
    L = [f'# RoyalBazaar - {cat_id}. Hypixel bazaar layout (see tools/hypixel_layout.py).',
         '# base_price = compression-multiplier x fair unit value of the source material.',
         '# ceiling_pct is capped per item so the bazaar can never outbid the NPC (no craft loop).',
         '',
         f'name: "{name}"', f'icon: "{icon}"', f'slot: {(row - 1) * 9 + (col - 1)}', '',
         'defaults:', f'  spread: {SPREAD}', '  elasticity: 50000', '  reversion_rate: 0.02',
         f'  floor_pct: {FLOOR_FLOOR}', f'  ceiling_pct: {HARD_CEILING}', '',
         '# Item families. Each is one icon on the category grid, opening a sub-menu.',
         'groups:']
    for n, (gid, gname, gicon, _) in enumerate(groups):
        L += [f'  {gid}:', f'    name: "&f{gname}"', f'    icon: "{gicon}"', f'    order: {n}']
    L += ['', 'items:']

    count = 0
    for gid, gname, _, items in groups:
        L.append(f'  # -- {gname} --')
        for iid in items:
            ok, err = emit_item(L, iid, gid)
            if ok:
                placed.add(iid)
                count += 1
            else:
                problems.append(err)
    with open(f'out/categories/{cat_id}.yml', 'w', encoding='utf-8', newline='') as fh:
        fh.write('\n'.join(L) + '\n')
    report.append((cat_id, len(groups), count))

print(f'{"category":16s} {"groups":>7s} {"items":>6s}  grid pages (28/page)')
for cat_id, g, n in report:
    print(f'  {cat_id:14s} {g:7d} {n:6d}  {-(-g // 28)}')
print(f'  TOTAL          {sum(r[1] for r in report):7d} {sum(r[2] for r in report):6d}')

if problems:
    print(f'\n!!! {len(problems)} PROBLEMS:')
    for p in problems:
        print('  ', p)

# The safety net: anything tradeable that the layout forgot.
catalogue = {i for i in ECO if i.startswith('enchanted_')} - layout.JUNK
DROPPED_PREFIX = ('enchanted_iron_', 'enchanted_golden_', 'enchanted_diamond_', 'enchanted_chainmail_')
DROPPED_EXACT = {'enchanted_bow', 'enchanted_shears', 'enchanted_flint_and_steel',
                 'enchanted_activator_rail', 'enchanted_daylight_sensor', 'enchanted_detector_rail',
                 'enchanted_dispenser', 'enchanted_dropper', 'enchanted_hopper', 'enchanted_lever',
                 'enchanted_light_weighted_pressure_plate', 'enchanted_minecart',
                 'enchanted_minecart_with_furnace', 'enchanted_minecart_with_hopper',
                 'enchanted_minecart_with_tnt', 'enchanted_piston', 'enchanted_powered_rail',
                 'enchanted_rail', 'enchanted_redstone_comparator', 'enchanted_redstone_torch',
                 'enchanted_repeater'}
KEEP = {'enchanted_iron', 'enchanted_iron_block', 'enchanted_gold_ingot', 'enchanted_gold_block',
        'enchanted_gold_nugget', 'enchanted_diamond', 'enchanted_diamond_block'}


def is_dropped(i):
    return i not in KEEP and (i.startswith(DROPPED_PREFIX) or i in DROPPED_EXACT)


missing = sorted(i for i in catalogue - placed if not is_dropped(i))
dropped = sorted(i for i in catalogue - placed if is_dropped(i))
print(f'\ndropped on purpose ({len(dropped)}): {layout.DROPPED_NOTE}')
if missing:
    print(f'\n!!! {len(missing)} TRADEABLE ITEMS NOT IN ANY GROUP (they would vanish):')
    for m in missing:
        print('  ', m)
else:
    print('\nno unassigned items: every tradeable item is in a group.')
