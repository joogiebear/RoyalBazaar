package com.mystipixel.royalbazaar.gui.menu;

import java.util.List;

/** A fixed slot from the {@code slots:} list; {@code index} is 0-based on the page. */
public record MenuSlot(int index,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick) {
}
