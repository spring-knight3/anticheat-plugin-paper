package com.springknight3.anticheat.config;

import org.bukkit.ChatColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageFormatter {
    private static final Pattern TOKEN = Pattern.compile("<([a-zA-Z_-]+)>|%([a-zA-Z_-]+)%");

    private MessageFormatter() {
    }

    public static String format(String template, Map<String, ?> placeholders) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Object value = placeholders.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(value == null ? matcher.group() : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return ChatColor.translateAlternateColorCodes('&', result.toString());
    }
}
