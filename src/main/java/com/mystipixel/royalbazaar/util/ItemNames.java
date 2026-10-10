package com.mystipixel.royalbazaar.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Translated vanilla item names for search. The server only ships English, so owners drop Minecraft
 * language files (e.g. {@code fr_fr.json}, copied from a client's assets) into the plugin's
 * {@code lang/} folder, and a search then also matches an item's name in any of those languages.
 *
 * <p>Matching ignores case and accents, so "epee" finds "Épée en diamant".
 */
public final class ItemNames {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern KEY = Pattern.compile("(?:item|block)\\.minecraft\\.([a-z0-9_]+)");

    /** Vanilla item id (e.g. {@code diamond_helmet}) to its normalised translated names. */
    private volatile Map<String, List<String>> byId = Map.of();

    /** Lower-cased, accent-free form used on both sides of a match. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String stripped = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).trim();
    }

    /** Re-read every {@code *.json} language file in {@code dir}; a missing folder means English only. */
    public void reload(File dir, Logger logger) {
        List<Map<String, String>> langs = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            Gson gson = new Gson();
            for (File file : files) {
                try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    Map<String, String> lang = gson.fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
                    if (lang != null) {
                        langs.add(lang);
                    }
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not read language file " + file.getName(), e);
                }
            }
        }
        load(langs);
        if (!langs.isEmpty()) {
            logger.info("Search: loaded item names from " + langs.size() + " language file(s).");
        }
    }

    /** Replace the names with those of {@code langs} (Minecraft language-file key/value maps). */
    void load(Collection<Map<String, String>> langs) {
        Map<String, List<String>> names = new HashMap<>();
        for (Map<String, String> lang : langs) {
            for (Map.Entry<String, String> entry : lang.entrySet()) {
                var m = KEY.matcher(entry.getKey());
                if (m.matches()) {
                    names.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(normalize(entry.getValue()));
                }
            }
        }
        byId = names;
    }

    /**
     * True when a translated name of the item contains {@code needle}, which must already be
     * {@link #normalize normalised}. Namespaced ids ({@code minecraft:stone}) are matched on their path.
     */
    public boolean translatedNameContains(String itemId, String needle) {
        String id = itemId.toLowerCase(Locale.ROOT);
        id = id.substring(id.indexOf(':') + 1);
        for (String name : byId.getOrDefault(id, List.of())) {
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
