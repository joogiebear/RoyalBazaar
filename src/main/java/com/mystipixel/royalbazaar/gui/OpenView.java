package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.gui.menu.MenuEffect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player state for the currently open bazaar inventory: which menu it is, the category/group/item
 * it is scoped to, the current page, and the click handlers keyed by raw slot index. Rebuilt on every
 * open so effect lists never need to be serialised onto the ItemStacks.
 */
public final class OpenView {

    private final String menuId;
    private final String categoryId;   // nullable
    private final String groupId;      // nullable (group menu)
    private final String itemId;       // nullable (product menu)
    private int page = 1;

    private final Map<Integer, List<MenuEffect>> leftHandlers = new HashMap<>();
    private final Map<Integer, List<MenuEffect>> rightHandlers = new HashMap<>();

    public OpenView(String menuId, String categoryId, String itemId) {
        this(menuId, categoryId, null, itemId);
    }

    public OpenView(String menuId, String categoryId, String groupId, String itemId) {
        this.menuId = menuId;
        this.categoryId = categoryId;
        this.groupId = groupId;
        this.itemId = itemId;
    }

    /** The search text when this view is a search result set, else null. */
    private String query;

    public String query() { return query; }

    public void setQuery(String query) { this.query = query; }

    public String menuId() { return menuId; }
    public String categoryId() { return categoryId; }
    public String groupId() { return groupId; }
    public String itemId() { return itemId; }
    public int page() { return page; }
    public void setPage(int page) { this.page = page; }

    public void bind(int slot, List<MenuEffect> left, List<MenuEffect> right) {
        if (left != null && !left.isEmpty()) {
            leftHandlers.put(slot, left);
        }
        if (right != null && !right.isEmpty()) {
            rightHandlers.put(slot, right);
        }
    }

    public void clearHandlers() {
        leftHandlers.clear();
        rightHandlers.clear();
    }

    public List<MenuEffect> left(int slot) {
        return leftHandlers.get(slot);
    }

    public List<MenuEffect> right(int slot) {
        return rightHandlers.get(slot);
    }
}
