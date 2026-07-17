"""The Hypixel bazaar layout, mapped onto the RoyalMC catalogue.

Hypixel's groups are editorial, not derivable: "Wheat & Seeds" merges two materials and holds
seven products; "Chicken & Feather" bundles three. So the structure is declared here by hand
rather than inferred from base-items, and gen_bazaar.py prices whatever it's handed.

Order within a group is the display order. Order of groups is the grid order.
Anything in the catalogue but absent here is reported as unassigned by the generator --
that report is the safety net against an item silently vanishing from the bazaar.
"""

# category id -> (display name, icon, main-menu (row, col), [(group id, name, icon, [item ids])])
# Item ids without a namespace are raw vanilla materials (minecraft:<id>); the rest are ecoitems:.
LAYOUT = {
    'farming': ('&aFarming', 'minecraft:golden_hoe', (2, 3), [
        ('wheat_seeds', 'Wheat & Seeds', 'minecraft:wheat',
         ['wheat', 'enchanted_wheat', 'enchanted_seeds', 'enchanted_bread', 'enchanted_hay_bale']),
        ('carrot', 'Carrot', 'minecraft:carrot',
         ['carrot', 'enchanted_carrot', 'enchanted_carrot_on_a_stick', 'enchanted_golden_carrot']),
        ('potato', 'Potato', 'minecraft:potato',
         ['potato', 'enchanted_potato', 'enchanted_poisonous_potato', 'enchanted_baked_potato']),
        ('pumpkin', 'Pumpkin', 'minecraft:pumpkin',
         ['enchanted_pumpkin', 'enchanted_pumpkin_pie']),
        ('melon', 'Melon', 'minecraft:melon',
         ['enchanted_melon_slice', 'enchanted_melon', 'enchanted_glistering_melon_slice']),
        ('mushroom', 'Mushrooms', 'minecraft:red_mushroom',
         ['enchanted_red_mushroom', 'enchanted_red_mushroom_block', 'enchanted_brown_mushroom',
          'enchanted_brown_mushroom_block', 'enchanted_mushroom_stew']),
        ('cocoa', 'Cocoa Beans', 'minecraft:cocoa_beans',
         ['enchanted_cocoa_beans', 'enchanted_cookie']),
        ('cactus', 'Cactus', 'minecraft:cactus',
         ['enchanted_cactus', 'enchanted_cactus_green']),
        ('sugar_cane', 'Sugar Cane', 'minecraft:sugar_cane',
         ['sugar_cane', 'enchanted_sugar_cane', 'enchanted_sugar', 'enchanted_paper']),
        ('apple', 'Apple', 'minecraft:apple',
         ['enchanted_apple', 'enchanted_golden_apple']),
        ('leather_beef', 'Leather & Beef', 'minecraft:leather',
         ['enchanted_leather', 'enchanted_raw_beef']),
        ('pork', 'Pork', 'minecraft:porkchop',
         ['enchanted_raw_porkchop', 'enchanted_cooked_porkchop']),
        ('chicken', 'Chicken & Feather', 'minecraft:chicken',
         ['enchanted_raw_chicken', 'enchanted_egg', 'enchanted_feather', 'enchanted_cake']),
        ('mutton', 'Mutton & Wool', 'minecraft:mutton',
         ['enchanted_raw_mutton', 'enchanted_cooked_mutton', 'enchanted_wool']),
        ('rabbit', 'Rabbit', 'minecraft:rabbit',
         ['enchanted_raw_rabbit', 'enchanted_rabbit_foot', 'enchanted_rabbit_hide']),
        ('nether_wart', 'Nether Warts', 'minecraft:nether_wart',
         ['enchanted_nether_wart']),
    ]),

    'mining': ('&bMining', 'minecraft:stone_pickaxe', (2, 5), [
        ('cobblestone', 'Cobblestone', 'minecraft:cobblestone',
         ['cobblestone', 'enchanted_cobblestone']),
        ('coal', 'Coal', 'minecraft:coal',
         ['coal', 'enchanted_coal', 'enchanted_charcoal', 'enchanted_coal_block']),
        ('iron', 'Iron', 'minecraft:iron_ingot',
         ['iron_block', 'enchanted_iron', 'enchanted_iron_block']),
        ('gold', 'Gold', 'minecraft:gold_ingot',
         ['enchanted_gold_nugget', 'enchanted_gold_ingot', 'enchanted_gold_block']),
        ('diamond', 'Diamond', 'minecraft:diamond',
         ['diamond_block', 'enchanted_diamond', 'enchanted_diamond_block']),
        ('lapis', 'Lapis', 'minecraft:lapis_lazuli',
         ['enchanted_lapis_lazuli', 'enchanted_lapis_lazuli_ore', 'enchanted_lapis_lazuli_block']),
        ('emerald', 'Emerald', 'minecraft:emerald',
         ['enchanted_emerald', 'enchanted_emerald_block']),
        ('redstone', 'Redstone', 'minecraft:redstone',
         ['enchanted_redstone_dust', 'enchanted_redstone_block']),
        ('quartz', 'Quartz', 'minecraft:quartz',
         ['enchanted_nether_quartz', 'enchanted_quartz_block', 'enchanted_chiseled_quartz_block']),
        ('obsidian', 'Obsidian', 'minecraft:obsidian', ['enchanted_obsidian']),
        ('end_stone', 'End Stone', 'minecraft:end_stone', ['enchanted_end_stone']),
        ('flint_gravel', 'Flint & Gravel', 'minecraft:flint',
         ['enchanted_flint', 'enchanted_gravel']),
        ('sand', 'Sand', 'minecraft:sand', ['enchanted_sand', 'enchanted_red_sand']),
        ('ice', 'Ice', 'minecraft:ice', ['enchanted_ice', 'enchanted_packed_ice']),
        ('snow', 'Snow', 'minecraft:snow_block', ['enchanted_snow_block']),
        ('glowstone', 'Glowstone', 'minecraft:glowstone',
         ['enchanted_glowstone_dust', 'enchanted_glowstone', 'enchanted_redstone_lamp']),
        ('netherrack', 'Netherrack', 'minecraft:netherrack',
         ['enchanted_netherrack', 'enchanted_nether_brick']),
        ('mycelium', 'Mycelium', 'minecraft:mycelium', ['enchanted_mycelium']),
        ('stone_glass', 'Stone & Glass', 'minecraft:bricks',
         ['enchanted_brick', 'enchanted_bricks', 'enchanted_glass']),
    ]),

    'combat': ('&cCombat', 'minecraft:iron_sword', (2, 7), [
        ('rotten_flesh', 'Rotten Flesh', 'minecraft:rotten_flesh', ['enchanted_rotten_flesh']),
        ('bone', 'Bone', 'minecraft:bone', ['enchanted_bone', 'enchanted_bone_meal']),
        ('arachnids', 'Arachnids', 'minecraft:string',
         ['enchanted_string', 'enchanted_spider_eye', 'enchanted_fermented_spider_eye']),
        ('gunpowder', 'Gunpowder', 'minecraft:gunpowder',
         ['enchanted_gunpowder', 'enchanted_firework_rocket', 'enchanted_firework_star']),
        ('ender_pearl', 'Ender Pearl', 'minecraft:ender_pearl',
         ['enchanted_ender_pearl', 'enchanted_eye_of_ender']),
        ('slimes', 'Slimes', 'minecraft:slime_ball',
         ['enchanted_slimeball', 'enchanted_slime_block']),
        ('magma_cream', 'Magma Cream', 'minecraft:magma_cream', ['enchanted_magma_cream']),
        ('blaze', 'Blaze Rod', 'minecraft:blaze_rod',
         ['enchanted_blaze_powder', 'enchanted_blaze_rod']),
        ('ghast_tear', 'Ghast Tear', 'minecraft:ghast_tear', ['enchanted_ghast_tear']),
        ('special', 'Special', 'minecraft:nether_star', ['enchanted_nether_star']),
    ]),

    'woods_fishes': ('&2Woods & Fishes', 'minecraft:fishing_rod', (3, 4), [
        ('wood', 'Wood', 'minecraft:oak_log',
         ['enchanted_oak_log', 'enchanted_spruce_log', 'enchanted_birch_log',
          'enchanted_dark_oak_log', 'enchanted_acacia_log', 'enchanted_jungle_log',
          'enchanted_jungle_sapling']),
        ('fish', 'Fish', 'minecraft:cod',
         ['enchanted_raw_cod', 'enchanted_cooked_cod', 'enchanted_raw_salmon',
          'enchanted_cooked_salmon', 'enchanted_clownfish', 'enchanted_pufferfish']),
        ('flowers', 'Flowers', 'minecraft:poppy',
         ['enchanted_poppy', 'enchanted_dandelion', 'enchanted_azure_bluet', 'enchanted_lilac',
          'enchanted_rose_bush', 'enchanted_large_fern', 'enchanted_dead_bush']),
        ('prismarine', 'Prismarine', 'minecraft:prismarine_shard',
         ['enchanted_prismarine_shard', 'enchanted_prismarine_crystals', 'enchanted_dark_prismarine']),
        ('clay', 'Clay', 'minecraft:clay_ball', ['enchanted_clay_ball']),
        ('sponge', 'Sponge', 'minecraft:sponge', ['enchanted_sponge']),
        ('lily_pad', 'Lily Pad', 'minecraft:lily_pad', ['enchanted_lily_pad']),
        ('ink_sac', 'Ink Sac', 'minecraft:ink_sac', ['enchanted_ink_sac', 'enchanted_lime_dye']),
        ('fishing_rod', 'Fishing Rod', 'minecraft:fishing_rod', ['enchanted_fishing_rod']),
    ]),

    'oddities': ('&dOddities', 'minecraft:ender_eye', (3, 6), [
        ('enchanting', 'Enchanting', 'minecraft:enchanting_table',
         ['enchanted_book', 'enchanted_book_and_quill', 'enchanted_bookshelf',
          'enchanted_enchantment_table']),
        ('exp', 'EXP Bottles', 'minecraft:experience_bottle',
         ['enchanted_bottle', 'enchanted_experience_bottle']),
        ('fuels', 'Fuels', 'minecraft:lava_bucket', ['enchanted_lava_bucket']),
        ('brewing', 'Brewing', 'minecraft:brewing_stand',
         ['enchanted_brewing_stand', 'enchanted_cauldron']),
        ('buckets', 'Buckets & Bowls', 'minecraft:bucket',
         ['enchanted_bowl', 'enchanted_bucket', 'enchanted_milk_bucket']),
        ('utility', 'Utility', 'minecraft:compass',
         ['enchanted_arrow', 'enchanted_compass', 'enchanted_clock', 'enchanted_empty_map',
          'enchanted_map', 'enchanted_lead', 'enchanted_name_tag']),
        ('decor', 'Decoration', 'minecraft:painting',
         ['enchanted_painting', 'enchanted_item_frame', 'enchanted_armor_stand',
          'enchanted_jukebox', 'enchanted_noteblock', 'enchanted_cobweb']),
        ('blocks', 'Workstations', 'minecraft:furnace',
         ['enchanted_furnace', 'enchanted_anvil']),
        ('beacon', 'Beacon', 'minecraft:beacon', ['enchanted_beacon']),
    ]),
}

# Deliberately not traded. Hypixel's bazaar is a pure materials market: gear is one-off and
# has durability (that's the auction house's job), and it has no redstone contraptions at all.
DROPPED_NOTE = 'tools/armor (gear -> RoyalAuctions) and redstone contraptions (no Hypixel equivalent)'

# Junk the item generator invented: unobtainable/creative blocks and spawn eggs.
JUNK = {'enchanted_bedrock', 'enchanted_command_block', 'enchanted_end_portal_frame',
        'enchanted_enderman_spawn_egg', 'enchanted_endermite_spawn_egg', 'enchanted_ghast_spawn_egg'}
