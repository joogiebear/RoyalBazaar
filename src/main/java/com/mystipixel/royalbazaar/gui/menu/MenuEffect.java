package com.mystipixel.royalbazaar.gui.menu;

import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One entry in a {@code left-click:} / {@code right-click:} effect list, matching the EcoMenus shape:
 * <pre>
 * - id: open_menu
 *   args:
 *     menu: bazaar_category
 * </pre>
 * {@code args} values may contain {@code %placeholders%}; they are resolved by the dispatcher.
 */
public record MenuEffect(String id, Map<String, Object> args) {

    /** Direction needed by this action, including navigation and quantity prompts. */
    public TradeSide tradeSide() {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "rbazaar_buy", "rbazaar_open_buy", "rbazaar_buy_prompt", "rbazaar_buy_amount_prompt" -> TradeSide.BUY;
            case "rbazaar_sell", "rbazaar_sell_prompt" -> TradeSide.SELL;
            default -> null; // sell-all filters its mixed scope in the service
        };
    }

    public String argString(String key, String def) {
        Object v = args.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public long argLong(String key, long def) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Parse a YAML effect list (list of maps with id/args). */
    public static List<MenuEffect> parseList(List<Map<?, ?>> raw) {
        List<MenuEffect> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Map<?, ?> entry : raw) {
            Object id = entry.get("id");
            if (id == null) {
                continue;
            }
            Object args = entry.get("args");
            Map<String, Object> argMap = new java.util.LinkedHashMap<>();
            if (args instanceof ConfigurationSection cs) {
                for (String k : cs.getKeys(false)) {
                    argMap.put(k, cs.get(k));
                }
            } else if (args instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    argMap.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.add(new MenuEffect(String.valueOf(id), argMap));
        }
        return out;
    }
}
