"""Generate RoyalBazaar category configs from the EcoItems catalogue.

Pricing model
-------------
unit(X)  = fair coins per single vanilla unit of X.
           Anchored to EcoShop where it exists: unit = 1.5 * NPC_sell
           (reproduces the hand-set wheat/carrot/potato prices exactly).
           Otherwise taken from CURATED below, which is built by crafting
           identity from anchored items wherever a recipe relates them.

base_price(enchanted_X) = multiplier * unit(X)   -- strict unit parity, no craft premium.

Safety: a player can always buy X from the NPC at NPC_buy. So the bazaar must never
pay more than that for the equivalent goods, or crafting becomes a money printer:

    base * ceiling_pct * (1 - spread) <= multiplier * NPC_buy(X)

Since base scales with the multiplier, the constraint reduces to a per-material
ceiling_pct that applies identically to the raw and its enchanted form.
"""
import os, math, collections

SPREAD = 0.05
HARD_CEILING = 3.0
FLOOR_FLOOR = 0.4
SAFETY = 0.98          # margin below the NPC-arbitrage break-even

# ---------------------------------------------------------------- inputs
SHOP = {}
for line in open('ecoshop_prices.csv', encoding='utf-8'):
    p = line.strip().split('|')
    if len(p) >= 5 and p[3]:
        SHOP[p[2]] = {'buy': float(p[3]), 'sell': float(p[4]) if p[4] else 0.0}
# the two prices we just fixed on the server
SHOP['diamond_block'] = {'buy': 1980.0, 'sell': 495.0}
SHOP['iron_block'] = {'buy': 270.0, 'sell': 72.0}

ECO = [l.strip().split('|') for l in open('eco_prices.csv', encoding='utf-8') if l.strip()]

# junk the generator invented: unobtainable/creative blocks and spawn eggs
DROP = {'enchanted_bedrock', 'enchanted_command_block', 'enchanted_end_portal_frame',
        'enchanted_enderman_spawn_egg', 'enchanted_endermite_spawn_egg',
        'enchanted_ghast_spawn_egg'}

# ---------------------------------------------------------------- curated units
# For materials with no NPC anchor. Derived by crafting identity from anchored
# items where a recipe exists (noted), otherwise judged against a comparable tier.
I, G, D, C, R = 12.0, 13.5, 82.5, 1.5, 3.0   # iron_ingot, gold_ingot, diamond, cobblestone, redstone
CURATED = {
    # --- compressed blocks: exactly 9x their part ---
    'coal_block': 40.5, 'gold_block': 9 * G, 'lapis_block': 40.5, 'redstone_block': 9 * R,
    'emerald_block': 607.5, 'packed_ice': 27.0,
    # --- fractions of anchored items ---
    'gold_nugget': G / 9, 'brick': 2.25, 'bone_meal': 1.0, 'charcoal': 4.5,
    # --- cheap naturals ---
    'lapis_ore': 27.0,          # drops ~6 lapis @ 4.5
    'netherrack': 1.5, 'end_stone': 3.0, 'ice': 3.0, 'red_sand': 1.5, 'flint': 3.0,
    'clay_ball': 3.0, 'dead_bush': 1.5, 'dandelion': 1.5, 'poppy': 1.5, 'azure_bluet': 1.5,
    'lilac': 3.0, 'rose_bush': 3.0, 'large_fern': 1.5, 'lily_pad': 4.5, 'wheat_seeds': 1.5,
    'jungle_sapling': 4.5, 'brown_mushroom': 3.0, 'red_mushroom': 3.0,
    'brown_mushroom_block': 6.0, 'red_mushroom_block': 6.0, 'mycelium': 15.0, 'cobweb': 15.0,
    'feather': 3.0, 'leather': 6.0, 'ink_sac': 4.5, 'lime_dye': 3.0, 'green_dye': 4.5,
    'paper': 3.0, 'blaze_rod': 24.0, 'rabbit_foot': 30.0, 'rabbit_hide': 4.5,
    'nether_brick': 6.0, 'chiseled_quartz_block': 15.0, 'dark_prismarine': 30.0,
    'ender_eye': 42.0,          # ender_pearl 30 + blaze_powder 12
    'experience_bottle': 30.0, 'nether_star': 4560.0, 'beacon': 6000.0,
    # --- food ---
    'apple': 6.0, 'golden_apple': 8 * G + 6.0, 'glistering_melon_slice': 8 * (G / 9) + 1.5,
    'egg': 3.0, 'beef': 4.5, 'porkchop': 4.5, 'chicken': 4.5, 'mutton': 4.5, 'rabbit': 6.0,
    'cooked_cod': 6.75, 'cooked_salmon': 6.75, 'cooked_porkchop': 6.75, 'cooked_mutton': 6.75,
    'baked_potato': 4.5, 'bread': 9.0, 'cookie': 3.0, 'melon': 13.5,
    'pumpkin_pie': 13.5, 'mushroom_stew': 7.5, 'bowl': 1.5, 'poisonous_potato': 1.5,
    'cake': 60.0, 'carrot_on_a_stick': 12.0,
    # --- crafted utility (iron/gold counts from vanilla recipes) ---
    'bucket': 3 * I, 'milk_bucket': 3 * I, 'lava_bucket': 3 * I + 9.0,
    'shears': 2 * I, 'flint_and_steel': I + 3.0, 'compass': 4 * I + R, 'clock': 4 * G + R,
    'map': 8.0 + R, 'filled_map': 12.0, 'minecart': 5 * I, 'furnace_minecart': 5 * I + 12.0,
    'hopper_minecart': 5 * I + 67.5, 'tnt_minecart': 5 * I + 30.0,
    'rail': 5.0, 'powered_rail': 15.0, 'detector_rail': 15.0, 'activator_rail': 12.0,
    'lever': 1.5, 'note_block': 45.0, 'jukebox': 90.0, 'redstone_lamp': 24.0 + 4 * R,
    'light_weighted_pressure_plate': 2 * G, 'iron_door': 2 * I, 'iron_trapdoor': 4 * I,
    'anvil': 31 * I, 'cauldron': 7 * I, 'brewing_stand': 24.0 + 3 * C, 'furnace': 8 * C,
    'enchanting_table': 4 * 45.0 + 2 * D + 15.0, 'bookshelf': 45.0, 'book': 15.0,
    'writable_book': 22.0, 'item_frame': 9.0, 'painting': 9.0, 'armor_stand': 9.0,
    'lead': 9.0, 'name_tag': 60.0, 'firework_rocket': 3.0, 'firework_star': 9.0,
    'glass_bottle': 1.5,
    # --- tools & armour: ingot count x unit ---
    'iron_shovel': I, 'iron_hoe': 2 * I, 'iron_axe': 3 * I, 'iron_pickaxe': 3 * I,
    'iron_boots': 4 * I, 'iron_helmet': 5 * I, 'iron_leggings': 7 * I, 'iron_chestplate': 8 * I,
    'golden_shovel': G, 'golden_hoe': 2 * G, 'golden_axe': 3 * G, 'golden_pickaxe': 3 * G,
    'golden_sword': 2 * G, 'golden_chestplate': 8 * G,
    'diamond_hoe': 2 * D, 'diamond_axe': 3 * D, 'diamond_pickaxe': 3 * D,
    'diamond_boots': 4 * D, 'diamond_helmet': 5 * D, 'diamond_leggings': 7 * D,
    'diamond_chestplate': 8 * D, 'chainmail_chestplate': 8 * I,
}

# ---------------------------------------------------------------- category meta
META = {
    'mining':            ('&bMining',        'minecraft:iron_pickaxe',  'Ores, stone and gemstones.'),
    'farming':           ('&aFarming',       'minecraft:wheat',         'Crops, seeds and animal produce.'),
    'foraging':          ('&2Foraging',      'minecraft:oak_log',       'Logs, saplings and woodland goods.'),
    'fishing':           ('&3Fishing',       'minecraft:fishing_rod',   'Fish, rods and ocean drops.'),
    'combat_mob_drops':  ('&cCombat',        'minecraft:diamond_sword', 'Mob drops and battle spoils.'),
    'redstone':          ('&4Redstone',      'minecraft:redstone',      'Redstone, rails and contraptions.'),
    'tools_armor':       ('&6Tools & Armor', 'minecraft:diamond_chestplate', 'Enchanted gear components.'),
    'utility_magic':     ('&dUtility',       'minecraft:ender_eye',     'Magic, brewing and rare curios.'),
    'misc':              ('&eMisc',          'minecraft:chest',         'Everything else worth trading.'),
}
# main-menu placement (row, column), 1-based like the GUI configs.
SLOTS = {'mining': (2, 3), 'farming': (2, 5), 'foraging': (2, 7),
         'fishing': (3, 3), 'combat_mob_drops': (3, 5), 'redstone': (3, 7),
         'tools_armor': (4, 3), 'utility_magic': (4, 5), 'misc': (4, 7)}

# Raw vanilla materials already traded in your hand-written categories. Preserved so the
# regeneration doesn't drop them; re-priced through the same model to close the ceiling
# breach the originals had (wheat at ceiling paid 8.55 vs the NPC's 8).
# 'verbatim' entries are emitted untouched -- they anchor to EcoShop via base_price: auto.
RAWS = {
    'farming': [
        {'id': 'wheat', 'item': 'minecraft:wheat'},
        {'id': 'carrot', 'item': 'minecraft:carrot'},
        {'id': 'potato', 'item': 'minecraft:potato'},
        {'id': 'sugar_cane', 'item': 'minecraft:sugar_cane', 'base_price': 4.0},
    ],
    'mining': [
        {'id': 'cobblestone', 'item': 'minecraft:cobblestone', 'elasticity': 100000},
        {'id': 'coal', 'item': 'minecraft:coal'},
        {'id': 'iron_block', 'verbatim': ['    item: "minecraft:iron_block"',
                                          '    base_price: auto', '    npc_floor: true',
                                          '    npc_ceiling: true']},
        {'id': 'diamond_block', 'verbatim': ['    item: "minecraft:diamond_block"',
                                             '    base_price: auto', '    npc_floor: true',
                                             '    npc_ceiling: true']},
    ],
}


def unit_of(base):
    if base in SHOP and SHOP[base]['sell']:
        return 1.5 * SHOP[base]['sell'], 'npc'
    if base in CURATED:
        return CURATED[base], 'curated'
    return None, None


def limits(base, unit):
    """Per-material floor/ceiling. Scale-invariant, so raw and enchanted share them.

    The ceiling solves base*ceiling*(1-spread) <= mult*NPC_buy for the break-even point,
    then takes a 2% haircut and rounds DOWN -- rounding up would sit a hair over the line
    and hand back a (tiny, but real) craft loop.
    """
    ceiling, floor = HARD_CEILING, FLOOR_FLOOR
    s = SHOP.get(base)
    if s:
        if s['buy']:                       # never outbid sourcing from the NPC
            ceiling = min(HARD_CEILING, SAFETY * s['buy'] / (unit * (1 - SPREAD)))
        if s['sell']:                      # never pay less than the NPC does
            floor = max(FLOOR_FLOOR, s['sell'] / unit)
    return math.floor(ceiling * 100) / 100, math.ceil(floor * 100) / 100


def fmt(v):
    return str(int(v)) if abs(v - round(v)) < 1e-9 else f'{v:.2f}'


cats = collections.defaultdict(list)
skipped = []
for cat, iid, buy, sell, base, mult, rarity in ECO:
    if not cat.startswith('enchanted_items/') or iid in DROP:
        if iid in DROP:
            skipped.append((iid, 'junk/unobtainable'))
        continue
    key = cat.split('/', 1)[1]
    if not base or not mult:
        skipped.append((iid, 'no base-item/multiplier'))
        continue
    unit, src = unit_of(base)
    if unit is None:
        skipped.append((iid, f'no unit value for base {base!r}'))
        continue
    m = float(mult)
    price = m * unit
    ceiling, floor = limits(base, unit)
    cats[key].append({'id': iid, 'base_price': price, 'ceiling': ceiling, 'floor': floor,
                      'base': base, 'mult': m, 'unit': unit, 'src': src, 'rarity': rarity})

os.makedirs('out/categories', exist_ok=True)
for key, items in cats.items():
    name, icon, blurb = META[key]
    row, col = SLOTS[key]
    items.sort(key=lambda x: x['base_price'])
    L = [f'# RoyalBazaar - {key}. Generated from the EcoItems catalogue.',
         '# base_price = compression-multiplier x fair unit value of the source material.',
         '# Unit values anchor to EcoShop (1.5 x NPC sell) where the NPC trades that material.',
         '# ceiling_pct is capped per item so the bazaar can never outbid the NPC (no craft loop).',
         '',
         f'name: "{name}"', f'icon: "{icon}"', f'slot: {(row - 1) * 9 + (col - 1)}', '',
         'defaults:', f'  spread: {SPREAD}', '  elasticity: 50000', '  reversion_rate: 0.02',
         f'  floor_pct: {FLOOR_FLOOR}', f'  ceiling_pct: {HARD_CEILING}', '', 'items:']

    # raw vanilla materials first, so the category reads raw -> compressed
    for raw in RAWS.get(key, []):
        L.append(f'  {raw["id"]}:')
        if 'verbatim' in raw:
            L.extend(raw['verbatim'])
            continue
        unit, src = unit_of(raw['id'])
        unit = raw.get('base_price', unit)
        ceiling, floor = limits(raw['id'], unit)
        L.append(f'    item: "{raw["item"]}"')
        L.append(f'    base_price: {fmt(unit)}')
        if 'elasticity' in raw:
            L.append(f'    elasticity: {raw["elasticity"]}')
        if abs(ceiling - HARD_CEILING) > 1e-9:
            L.append(f'    ceiling_pct: {fmt(ceiling)}')
        if abs(floor - FLOOR_FLOOR) > 1e-9:
            L.append(f'    floor_pct: {fmt(floor)}')

    for it in items:
        L.append(f'  {it["id"]}:')
        L.append(f'    item: "ecoitems:{it["id"]}"')
        L.append(f'    base_price: {fmt(it["base_price"])}')
        if abs(it['ceiling'] - HARD_CEILING) > 1e-9:
            L.append(f'    ceiling_pct: {fmt(it["ceiling"])}')
        if abs(it['floor'] - FLOOR_FLOOR) > 1e-9:
            L.append(f'    floor_pct: {fmt(it["floor"])}')
        L.append(f'    # {fmt(it["mult"])}x {it["base"]} @ {fmt(it["unit"])}/unit ({it["src"]})')
    # newline='' -- the server reads LF; Windows text mode would smuggle in CRLF.
    with open(f'out/categories/{key}.yml', 'w', encoding='utf-8', newline='') as fh:
        fh.write('\n'.join(L) + '\n')

print(f'wrote {len(cats)} categories, {sum(len(v) for v in cats.values())} items')
for k in sorted(cats): print(f'  {k:20s} {len(cats[k]):3d} items')
print(f'\nskipped {len(skipped)}:')
for i, r in skipped: print(f'  {i:34s} {r}')
