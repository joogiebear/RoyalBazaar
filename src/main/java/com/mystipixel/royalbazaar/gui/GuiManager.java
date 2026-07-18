package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.gui.menu.ItemSpec;
import com.mystipixel.royalbazaar.gui.menu.MenuEffect;
import com.mystipixel.royalbazaar.gui.menu.MenuManager;
import com.mystipixel.royalbazaar.gui.menu.MenuSlot;
import com.mystipixel.royalbazaar.gui.menu.MenuTemplate;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import com.mystipixel.royalbazaar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opens and renders the three bazaar menus and tracks each viewer's {@link OpenView}. Rendering:
 * paint the mask filler, place fixed {@code slots} (binding their click effects), then — for the
 * category menu — page the category's items into the mask's {@code 0} slots using the content template.
 */
public final class GuiManager {

    private final MenuManager menus;
    private final MarketManager market;
    private final BazaarService service;
    private final EcoHook eco;

    private final Map<UUID, OpenView> views = new HashMap<>();

    public GuiManager(MenuManager menus, MarketManager market, BazaarService service, EcoHook eco) {
        this.menus = menus;
        this.market = market;
        this.service = service;
        this.eco = eco;
    }

    public OpenView viewOf(Player player) {
        return views.get(player.getUniqueId());
    }

    public void forget(Player player) {
        views.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ open


    /**
     * The category grid. A category that declares {@code groups:} pages its group icons here (each
     * opening the group menu); a flat one pages its products directly, as before. Un-grouped items in
     * a grouped category still appear alongside the group icons, so nothing can go missing.
     */
    /**
     * What {@code /bazaar} opens: the configured default category, falling back to the first configured
     * one when none is set or the id no longer matches a category (renamed, removed), rather than
     * opening nothing at all.
     */
    public void openDefault(Player player) {
        String preferred = service.config().defaultCategory();
        if (preferred != null && market.category(preferred) != null) {
            openCategory(player, preferred, 1);
            return;
        }
        // No default configured (or it names a category that no longer exists): open the first one.
        // The category rail is the navigation, so landing anywhere in it puts every category one
        // click away.
        for (CategoryConfig cat : market.categories()) {
            openCategory(player, cat.id(), 1);
            return;
        }
        // Nothing to show: no categories are configured. Say so rather than opening an empty menu.
        player.sendMessage(Text.chat("&cThe bazaar has no categories configured."));
    }

    /**
     * Results for a search, rendered with the category template so paging, icons and buy/sell clicks all
     * behave exactly as they do inside a category. Matches on the item's configured display name and its
     * id, so both "enchanted cobblestone" and "cobble" find something.
     */
    public void openSearch(Player player, String query, int page) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<MarketItem> hits = new ArrayList<>();
        if (!needle.isEmpty()) {
            for (MarketItem item : market.all()) {
                String name = item.displayName() == null ? "" : item.displayName().toLowerCase(Locale.ROOT);
                if (name.contains(needle) || item.id().toLowerCase(Locale.ROOT).contains(needle)) {
                    hits.add(item);
                }
            }
        }

        MenuTemplate tmpl = menus.get("bazaar_category");
        OpenView view = new OpenView("bazaar_search", null, null);
        view.setQuery(query);
        view.setPage(Math.max(1, page));

        Map<String, String> base = new HashMap<>();
        base.put("rbazaar_category", "Search: " + query + " (" + hits.size() + ")");
        Inventory inv = Bukkit.createInventory(player, tmpl.size(), Text.color(applyMap(tmpl.title(), base)));
        tmpl.applyFiller(inv);
        placeFixedSlots(tmpl, inv, view, base);
        placeArrows(tmpl, inv, view);
        placeCategoryRail(tmpl, inv, view, null);
        paginateItems(tmpl, inv, view, player, hits);

        player.openInventory(inv);
        views.put(player.getUniqueId(), view);
    }

    public void openCategory(Player player, String categoryId, int page) {
        CategoryConfig cat = market.category(categoryId);
        if (cat == null) {
            return;
        }
        MenuTemplate tmpl = menus.get("bazaar_category");
        OpenView view = new OpenView("bazaar_category", categoryId, null);
        view.setPage(Math.max(1, page));

        Map<String, String> base = new HashMap<>();
        base.put("rbazaar_category", cat.displayName());
        Inventory inv = Bukkit.createInventory(player, tmpl.size(), Text.color(applyMap(tmpl.title(), base)));
        tmpl.applyFiller(inv);
        placeFixedSlots(tmpl, inv, view, base);
        placeArrows(tmpl, inv, view);
        placeCategoryRail(tmpl, inv, view, cat.id());
        if (cat.hasGroups()) {
            paginateGroups(tmpl, inv, view, player, cat);
        } else {
            paginateItems(tmpl, inv, view, player, market.itemsIn(cat.id()));
        }

        player.openInventory(inv);
        views.put(player.getUniqueId(), view);
    }

    /** One group's products: category → group → here. */
    public void openGroup(Player player, String categoryId, String groupId, int page) {
        CategoryConfig cat = market.category(categoryId);
        if (cat == null) {
            return;
        }
        CategoryConfig.Group group = cat.groups().stream()
                .filter(g -> g.id().equals(groupId))
                .findFirst().orElse(null);
        if (group == null) {
            openCategory(player, categoryId, 1); // stale/typo'd group — don't strand the player
            return;
        }
        MenuTemplate tmpl = menus.get("bazaar_group");
        OpenView view = new OpenView("bazaar_group", categoryId, groupId, null);
        view.setPage(Math.max(1, page));

        List<MarketItem> items = market.itemsInGroup(categoryId, groupId);
        Map<String, String> base = service.groupPlaceholders(cat, group, items);
        Inventory inv = Bukkit.createInventory(player, tmpl.size(), Text.color(applyMap(tmpl.title(), base)));
        tmpl.applyFiller(inv);
        placeFixedSlots(tmpl, inv, view, base);
        placeArrows(tmpl, inv, view);
        paginateItems(tmpl, inv, view, player, items);

        player.openInventory(inv);
        views.put(player.getUniqueId(), view);
    }

    /**
     * The instant-buy menu for one item: fixed quantities, "fill my inventory", and a custom amount.
     *
     * <p>Split out from the product page so that page can stay a summary — the item, its live stats, and
     * one button per direction — instead of a wall of quantity buttons.
     */
    public void openBuy(Player player, String itemId) {
        MarketItem item = itemId == null ? null : market.get(itemId);
        if (item == null) {
            return;
        }
        MenuTemplate tmpl = menus.get("bazaar_buy");
        if (tmpl == null) {
            openProduct(player, itemId);   // menu file missing — don't strand the player
            return;
        }
        OpenView view = new OpenView("bazaar_buy", item.categoryId(), itemId);
        Map<String, String> ph = service.placeholders(item, player);

        Inventory inv = Bukkit.createInventory(player, tmpl.size(), Text.color(applyMap(tmpl.title(), ph)));
        tmpl.applyFiller(inv);
        placeFixedSlots(tmpl, inv, view, ph);

        player.openInventory(inv);
        views.put(player.getUniqueId(), view);
    }

    public void openProduct(Player player, String itemId) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return;
        }
        MenuTemplate tmpl = menus.get("bazaar_product");
        OpenView view = new OpenView("bazaar_product", item.categoryId(), itemId);
        Map<String, String> ph = service.placeholders(item, player);

        Inventory inv = Bukkit.createInventory(player, tmpl.size(), Text.color(applyMap(tmpl.title(), ph)));
        tmpl.applyFiller(inv);
        placeFixedSlots(tmpl, inv, view, ph);

        player.openInventory(inv);
        views.put(player.getUniqueId(), view);
    }

    /** Re-render whatever the player currently has open (after a trade moves prices). */
    public void refresh(Player player) {
        OpenView v = views.get(player.getUniqueId());
        if (v == null) {
            return;
        }
        switch (v.menuId()) {
            case "bazaar_category" -> openCategory(player, v.categoryId(), v.page());
            case "bazaar_group" -> openGroup(player, v.categoryId(), v.groupId(), v.page());
            case "bazaar_product" -> openProduct(player, v.itemId());
            case "bazaar_buy" -> openBuy(player, v.itemId());
            case "bazaar_search" -> openSearch(player, v.query(), v.page());
            default -> openDefault(player);
        }
    }

    // ------------------------------------------------------------------ rendering helpers

    /**
     * Draw one icon per category down the rail, marking the one being viewed. Drawn after the mask so it
     * sits on top of the filler, which means a menu gains a rail purely by declaring {@code category-rail}
     * in its YAML — no mask changes needed.
     *
     * <p>A category beyond the last rail slot is not reachable from the rail, so keep the rail at least
     * as long as the category list, or point a button at the ones that overflow.
     */
    /**
     * Place every configured category at the {@code slot} its own file declares.
     *
     * <p>Driving this from the category files means adding a category is enough to make it appear —
     * placement lives next to the category's own settings rather than in a separate menu file.
     */
    private void placeCategoryIcons(Inventory inv, OpenView view) {
        for (CategoryConfig cat : market.categories()) {
            int index = cat.slot();
            if (!inBounds(index, inv)) {
                continue;
            }
            Map<String, String> ph = new HashMap<>();
            ph.put("rbazaar_category", cat.displayName());
            ItemStack icon = ItemSpec.parse(cat.icon() + " name:\"" + cat.displayName() + "\"")
                    .build(eco, ph, List.of("&7Click to browse this category."));
            inv.setItem(index, icon);
            view.bind(index, List.of(new MenuEffect("open_menu",
                    Map.of("menu", "bazaar_category", "category", cat.id()))), List.of());
        }
    }

    private void placeCategoryRail(MenuTemplate tmpl, Inventory inv, OpenView view, String selectedId) {
        MenuTemplate.Rail rail = tmpl.rail();
        if (rail == null) {
            return;
        }
        int i = 0;
        for (CategoryConfig cat : market.categories()) {
            if (i >= rail.indices().size()) {
                break;
            }
            int index = rail.indices().get(i++);
            if (!inBounds(index, inv)) {
                continue;
            }
            boolean selected = cat.id().equalsIgnoreCase(selectedId);
            Map<String, String> ph = new HashMap<>();
            ph.put("rbazaar_category", cat.displayName());
            List<String> lore = new ArrayList<>();
            for (String line : rail.lore()) {
                lore.add(applyMap(line, ph));
            }
            if (selected) {
                lore.add("&aCurrently viewing");
            }
            ItemStack icon = ItemSpec.parse(cat.icon() + " name:\"" + cat.displayName() + "\"").build(eco, ph, lore);
            if (selected && rail.glintSelected()) {
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    icon.setItemMeta(meta);
                }
            }
            inv.setItem(index, icon);
            // Clicking a rail icon opens that category. Reuses the generic open_menu effect rather than
            // inventing a rail-specific one, so the behaviour matches a category button anywhere else.
            view.bind(index, List.of(new MenuEffect("open_menu",
                    Map.of("menu", "bazaar_category", "category", cat.id()))), List.of());
        }
    }

    private void placeFixedSlots(MenuTemplate tmpl, Inventory inv, OpenView view, Map<String, String> ph) {
        for (MenuSlot slot : tmpl.slots()) {
            if (slot.index() < 0 || slot.index() >= inv.getSize()) {
                continue;
            }
            inv.setItem(slot.index(), slot.item().build(eco, ph, slot.lore()));
            view.bind(slot.index(), resolveEffects(slot.leftClick(), ph), resolveEffects(slot.rightClick(), ph));
        }
    }

    private void placeArrows(MenuTemplate tmpl, Inventory inv, OpenView view) {
        // Backwards is owned here (shown only past page 1); forwards is owned by paginateItems,
        // which knows whether a next page actually exists.
        MenuTemplate.Arrow back = tmpl.backwards();
        if (back != null && back.enabled() && view.page() > 1 && inBounds(back.index(), inv)) {
            inv.setItem(back.index(), back.item().build(eco, Map.of(), List.of()));
            view.bind(back.index(), List.of(new MenuEffect("rbazaar_prev_page", Map.of())), null);
        }
    }

    private void paginateItems(MenuTemplate tmpl, Inventory inv, OpenView view, Player player,
                               List<MarketItem> items) {
        MenuTemplate.Content content = tmpl.content();
        List<Integer> slots = tmpl.contentSlots();
        if (content == null || slots.isEmpty()) {
            return;
        }
        paginate(tmpl, inv, view, items.size(), idx -> items.get(idx).pinnedSlot(), (idx, slot) -> {
            MarketItem item = items.get(idx);
            Map<String, String> ph = service.placeholders(item, player);
            inv.setItem(slot, content.template().build(eco, ph, content.lore()));
            view.bind(slot, resolveEffects(content.leftClick(), ph), resolveEffects(content.rightClick(), ph));
        });
    }

    /**
     * The group grid: one icon per declared group, each carrying a live summary, followed by any
     * items the config left un-grouped so they can't silently disappear from the category.
     */
    private void paginateGroups(MenuTemplate tmpl, Inventory inv, OpenView view, Player player,
                                CategoryConfig cat) {
        MenuTemplate.Content groupContent = tmpl.groupContent();
        MenuTemplate.Content content = tmpl.content();
        if (groupContent == null || tmpl.contentSlots().isEmpty()) {
            // No group-content template authored — fall back to a flat listing rather than an empty menu.
            paginateItems(tmpl, inv, view, player, market.itemsIn(cat.id()));
            return;
        }
        List<CategoryConfig.Group> groups = cat.groups();
        List<MarketItem> loose = market.ungroupedItemsIn(cat.id());

        paginate(tmpl, inv, view, groups.size() + loose.size(),
                idx -> idx < groups.size() ? groups.get(idx).slot() : loose.get(idx - groups.size()).pinnedSlot(),
                (idx, slot) -> {
            if (idx < groups.size()) {
                CategoryConfig.Group group = groups.get(idx);
                Map<String, String> ph = service.groupPlaceholders(
                        cat, group, market.itemsInGroup(cat.id(), group.id()));
                inv.setItem(slot, groupContent.template().build(eco, ph, groupContent.lore()));
                view.bind(slot, resolveEffects(groupContent.leftClick(), ph),
                        resolveEffects(groupContent.rightClick(), ph));
            } else if (content != null) {
                MarketItem item = loose.get(idx - groups.size());
                Map<String, String> ph = service.placeholders(item, player);
                inv.setItem(slot, content.template().build(eco, ph, content.lore()));
                view.bind(slot, resolveEffects(content.leftClick(), ph), resolveEffects(content.rightClick(), ph));
            }
        });
    }

    /**
     * Shared paging: walk the content slots for the current page, hand each (entryIndex, slot) to the
     * renderer, and expose the forwards arrow only when a next page actually exists.
     */
    /**
     * Lay entries into the content region, honouring any that pin themselves to a fixed slot.
     *
     * <p>{@code pinnedSlotOf} gives an entry's configured slot, or -1 to let it flow. Pinned entries are
     * drawn on the first page at exactly their slot and the flowing entries fill what's left, so an admin
     * can anchor a few items and let the rest arrange themselves. Later pages have the whole grid free,
     * since the pinned entries have already been shown.
     */
    private void paginate(MenuTemplate tmpl, Inventory inv, OpenView view, int total,
                          java.util.function.IntUnaryOperator pinnedSlotOf, SlotRenderer renderer) {
        List<Integer> slots = tmpl.contentSlots();
        if (slots.isEmpty()) {
            return;
        }

        List<Integer> pinned = new ArrayList<>();
        List<Integer> flowing = new ArrayList<>();
        Set<Integer> reserved = new HashSet<>();
        for (int i = 0; i < total; i++) {
            int pin = pinnedSlotOf.applyAsInt(i);
            if (pin >= 0 && inBounds(pin, inv)) {
                pinned.add(i);
                reserved.add(pin);
            } else {
                flowing.add(i);
            }
        }

        List<Integer> firstPageSlots = new ArrayList<>();
        for (int slot : slots) {
            if (!reserved.contains(slot)) {
                firstPageSlots.add(slot);
            }
        }

        int page = Math.max(1, view.page());
        if (page == 1) {
            for (int index : pinned) {
                renderer.render(index, pinnedSlotOf.applyAsInt(index));
            }
        }

        List<Integer> pageSlots = page == 1 ? firstPageSlots : slots;
        int from = page == 1 ? 0 : firstPageSlots.size() + (page - 2) * slots.size();
        for (int i = 0; i < pageSlots.size(); i++) {
            int idx = from + i;
            if (idx >= flowing.size()) {
                break;
            }
            renderer.render(flowing.get(idx), pageSlots.get(i));
        }

        int nextFrom = from + pageSlots.size();
        MenuTemplate.Arrow fwd = tmpl.forwards();
        if (fwd != null && fwd.enabled() && inBounds(fwd.index(), inv) && nextFrom < flowing.size()) {
            inv.setItem(fwd.index(), fwd.item().build(eco, Map.of(), List.of()));
            view.bind(fwd.index(), List.of(new MenuEffect("rbazaar_next_page", Map.of())), null);
        }
    }

    @FunctionalInterface
    private interface SlotRenderer {
        void render(int entryIndex, int slot);
    }

    /**
     * Resolve {@code %placeholder%} tokens inside effect args against the render placeholder map,
     * so effects on both fixed slots (product buy/sell buttons) and generated grid items get the
     * concrete item id (and any other placeholder) baked in before they're bound to a slot.
     */
    private List<MenuEffect> resolveEffects(List<MenuEffect> effects, Map<String, String> ph) {
        if (effects == null || effects.isEmpty()) {
            return effects;
        }
        List<MenuEffect> out = new java.util.ArrayList<>(effects.size());
        for (MenuEffect e : effects) {
            Map<String, Object> args = new HashMap<>(e.args());
            args.replaceAll((k, v) -> resolveValue(v, ph));
            out.add(new MenuEffect(e.id(), args));
        }
        return out;
    }

    private Object resolveValue(Object value, Map<String, String> ph) {
        if (!(value instanceof String s) || s.indexOf('%') < 0) {
            return value;
        }
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("%" + e.getKey() + "%", e.getValue());
        }
        return s;
    }

    private boolean inBounds(int index, Inventory inv) {
        return index >= 0 && index < inv.getSize();
    }

    private String applyMap(String input, Map<String, String> ph) {
        String out = input == null ? "" : input;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }
}
