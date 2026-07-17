"""Generate the EcoItems configs Hypixel's bazaar expects but this pack is missing.

Lore carries flavour and the recipe only -- never prices. eco re-injects an item's lore into
any GUI, so baked prices duplicate the bazaar menu's own live lines (and vanilla items, having
no config, show only one set). They also go stale the moment the market moves. See repricer.py.

Written to match the existing pack byte-for-byte in shape: same 5x32 recipe grid, same lore
skeleton, same shop-pricing block. Prices come from the same unit model as the bazaar
(gen_bazaar.py), so a new item can't be the one that's mispriced.

Skipped deliberately: hard_stone, sulphur -- Skyblock inventions with no vanilla base item.
They'd need a custom texture and a drop source, not just a config.
"""
import os

SPREAD = 0.05
OUT = 'out/newitems'

# id -> (base item, folder, unit value, rarity, pretty base name for the lore)
# Unit values follow gen_bazaar.py: EcoShop-anchored (1.5 x NPC sell) where it exists,
# else crafting identity (1 wool = 4 string), else judged against a comparable tier.
NEW = {
    # --- farming ---
    'enchanted_wheat':      ('wheat',        'farming',  3.0,  'uncommon', 'Wheat'),
    'enchanted_sugar':      ('sugar',        'farming',  4.0,  'uncommon', 'Sugar'),
    'enchanted_sugar_cane': ('sugar_cane',   'farming',  4.0,  'uncommon', 'Sugar Cane'),
    'enchanted_wool':       ('white_wool',   'farming', 12.0,  'uncommon', 'Wool'),   # 4 string
    # --- combat ---
    'enchanted_string':      ('string',      'combat_mob_drops',  3.0, 'uncommon', 'String'),
    'enchanted_spider_eye':  ('spider_eye',  'combat_mob_drops',  4.5, 'uncommon', 'Spider Eye'),
    'enchanted_slimeball':   ('slimeball',   'combat_mob_drops',  6.0, 'uncommon', 'Slimeball'),
    'enchanted_slime_block': ('slime_block', 'combat_mob_drops', 54.0, 'rare',     'Slime Block'),  # 9 slimeball
    # --- mining ---
    'enchanted_gravel':      ('gravel',      'mining',  1.5, 'uncommon', 'Gravel'),
    'enchanted_snow_block':  ('snow_block',  'mining',  6.0, 'uncommon', 'Snow Block'),  # 4 snowball
    # --- woods & fishes ---
    'enchanted_spruce_log':  ('spruce_log',   'foraging',  4.5, 'uncommon', 'Spruce Log'),
    'enchanted_clownfish':   ('tropical_fish', 'fishing', 12.0, 'rare',     'Clownfish'),
    'enchanted_sponge':      ('sponge',       'fishing',  30.0, 'rare',     'Sponge'),
}

TEMPLATE = """item:
  item: {base} unbreaking:1 hide_enchants hide_attributes
  display-name: '&b{display}'
  lore:
  - '&7A compressed RoyalMC material.'
  - ''
  - '&8A compact item used in advanced crafting.'
  - '&8Crafted from 160 {base_name}'
  craftable: true
  recipe-give-amount: 1
  crafting-permission: royalmc.ecoitems.craft.{iid}
  shapeless: false
  recipe:
  - ''
  - {base} 32
  - ''
  - {base} 32
  - {base} 32
  - {base} 32
  - ''
  - {base} 32
  - ''
slot: any
rarity: {rarity}
shop-pricing:
  currency: coins
  buy: {buy}
  sell: {sell}
  base-item: {base}
  compression-multiplier: 160
  permission: royalmc.ecoitems.craft.{iid}
  source: generated_for_royalmc_hypixel_parity
effects: []
conditions: []
"""

for iid, (base, folder, unit, rarity, base_name) in NEW.items():
    price = 160 * unit
    buy, sell = round(price * (1 + SPREAD)), round(price * (1 - SPREAD))
    display = 'Enchanted ' + base_name
    os.makedirs(f'{OUT}/{folder}', exist_ok=True)
    body = TEMPLATE.format(base=base, display=display, base_name=base_name, iid=iid,
                           rarity=rarity, buy=buy, sell=sell)
    with open(f'{OUT}/{folder}/{iid}.yml', 'w', encoding='utf-8', newline='') as fh:
        fh.write(body)
    print(f'  {iid:24s} 160x {base:14s} @ {unit:6.1f}/unit -> base {price:9.1f}  buy {buy:>8,} sell {sell:>8,}')

print(f'\nwrote {len(NEW)} new EcoItems configs to {OUT}/')
