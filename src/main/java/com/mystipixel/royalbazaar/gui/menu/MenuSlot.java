package com.mystipixel.royalbazaar.gui.menu;

import java.util.List;

/**
 * A fixed, hand-placed slot from the {@code slots:} list — its resolved 0-based index on the page,
 * the item to render, its lore, and the effect lists for left/right clicks.
 */
public record MenuSlot(int index,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick) {
}
