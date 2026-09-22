package com.cassieroseza;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Crestfall extends JavaPlugin implements TabExecutor {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern CMI_GRADIENT_PATTERN = Pattern.compile("(?i)\\{#([0-9a-f]{6})>}(.+?)\\{#([0-9a-f]{6})<}");
    private static final Pattern CMI_HEX_PATTERN = Pattern.compile("(?i)[{<]#([0-9a-f]{6})[}>]");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final List<String> announcements = new ArrayList<>();
    private final List<TimeWindow> oddHourWindows = new ArrayList<>();

    private BukkitTask scheduler;
    private ZoneId zoneId = ZoneId.systemDefault();
    private long lastAnnouncementMillis = 0L;
    private int announcementsToday = 0;
    private String currentDay = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("announcements.txt");
        reloadLoreConfig();

        if (getCommand("crestfall") != null) {
            getCommand("crestfall").setExecutor(this);
            getCommand("crestfall").setTabCompleter(this);
        }

        startScheduler();
        getLogger().info("Crestfall loaded with " + announcements.size() + " announcement lines.");
        getLogger().info("PlaceholderAPI support: " + (isPlaceholderApiEnabled() ? "enabled" : "not detected"));
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crestfall.reload")) {
                sender.sendMessage(color("&cYou do not have permission to reload Crestfall."));
                return true;
            }

            reloadConfig();
            reloadLoreConfig();
                startScheduler();
                sender.sendMessage(color("&aCrestfall reloaded. Loaded &f" + announcements.size() + " &alines."));
            return true;
        }

        if (args[0].equalsIgnoreCase("force")) {
            if (!sender.hasPermission("crestfall.force")) {
                sender.sendMessage(color("&cYou do not have permission to force Crestfall announcements."));
                return true;
            }

            if (announcements.isEmpty()) {
                sender.sendMessage(color("&cNo announcements are loaded."));
                return true;
            }

            announce(randomAnnouncement(), true);
            sender.sendMessage(color("&aForced a lore announcement."));
            return true;
        }

        sender.sendMessage(color("&cUsage: /" + label + " <status|reload|force>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String option : Arrays.asList("status", "reload", "force")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    completions.add(option);
                }
            }
            return completions;
        }

        return Collections.emptyList();
    }

    private void reloadLoreConfig() {
        announcements.clear();
        oddHourWindows.clear();

        FileConfiguration config = getConfig();
        zoneId = parseZone(config.getString("timezone", ZoneId.systemDefault().getId()));

        for (String rawWindow : config.getStringList("odd-hours.windows")) {
            TimeWindow window = TimeWindow.parse(rawWindow);
            if (window == null) {
                getLogger().warning("Ignoring invalid odd-hours window: " + rawWindow);
                continue;
            }
            oddHourWindows.add(window);
        }

        loadAnnouncements();
        resetDailyCounterIfNeeded();
    }

    private void startScheduler() {
        if (scheduler != null) {
            scheduler.cancel();
        }

        long intervalSeconds = Math.max(5, getConfig().getLong("schedule.check-interval-seconds", 900));
        scheduler = Bukkit.getScheduler().runTaskTimer(this, this::attemptRandomAnnouncement, 20L * 5L, intervalSeconds * 20L);
    }

    private void attemptRandomAnnouncement() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("enabled", true) || announcements.isEmpty()) {
            return;
        }

        resetDailyCounterIfNeeded();

        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        if (onlinePlayers < config.getInt("schedule.min-online-players", 1)) {
            return;
        }

        int maxPerDay = config.getInt("schedule.max-announcements-per-real-day", 0);
        if (maxPerDay > 0 && announcementsToday >= maxPerDay) {
            return;
        }

        long cooldownMillis = Math.max(0, config.getLong("schedule.cooldown-minutes", 30)) * 60_000L;
        if (System.currentTimeMillis() - lastAnnouncementMillis < cooldownMillis) {
            return;
        }

        if (config.getBoolean("odd-hours.enabled", true) && !isOddHour()) {
            return;
        }

        double chance = Math.max(0.0D, Math.min(100.0D, config.getDouble("schedule.chance-percent", 12.5D)));
        if (ThreadLocalRandom.current().nextDouble(100.0D) > chance) {
            return;
        }

        announce(randomAnnouncement(), false);
    }

    private void announce(String rawMessage, boolean forced) {
        FileConfiguration config = getConfig();
        String message = config.getString("format.prefix", "") + rawMessage + config.getString("format.suffix", "");

        if (config.getBoolean("delivery.chat.enabled", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(renderMessage(message, player));
            }
        }

        if (config.getBoolean("delivery.console.enabled", true)) {
            getLogger().info(plainText(message, null));
        }

        if (config.getBoolean("delivery.actionbar.enabled", false)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(renderMessage(message, player));
            }
        }

        if (config.getBoolean("delivery.title.enabled", false)) {
            String title = config.getString("delivery.title.title", "&5Crestfall stirs...");
            String subtitle = config.getString("delivery.title.subtitle", rawMessage);
            int fadeIn = config.getInt("delivery.title.fade-in-ticks", 10);
            int stay = config.getInt("delivery.title.stay-ticks", 70);
            int fadeOut = config.getInt("delivery.title.fade-out-ticks", 20);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.showTitle(net.kyori.adventure.title.Title.title(
                        renderMessage(title, player),
                        renderMessage(subtitle, player),
                        net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(fadeIn * 50L),
                                java.time.Duration.ofMillis(stay * 50L),
                                java.time.Duration.ofMillis(fadeOut * 50L)
                        )
                ));
            }
        }

        playSound();

        lastAnnouncementMillis = System.currentTimeMillis();
        if (!forced || getConfig().getBoolean("schedule.forced-announcements-count-toward-limit", false)) {
            announcementsToday++;
        }
    }

    private void playSound() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("delivery.sound.enabled", true)) {
            return;
        }

        String soundName = config.getString("delivery.sound.name", "ENTITY_ENDERMAN_AMBIENT");
        if (soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound = resolveSound(soundName);
        if (sound == null) {
            getLogger().warning("Invalid configured sound: " + soundName);
            return;
        }

        float volume = (float) config.getDouble("delivery.sound.volume", 0.7D);
        float pitch = (float) config.getDouble("delivery.sound.pitch", 0.85D);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private Sound resolveSound(String soundName) {
        Sound sound = findSound(soundName.toLowerCase(Locale.ROOT));
        if (sound != null) {
            return sound;
        }

        String normalized = soundName.toLowerCase(Locale.ROOT).replace('_', '.');
        return findSound(normalized);
    }

    private Sound findSound(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null && !key.contains(":")) {
            namespacedKey = NamespacedKey.minecraft(key);
        }
        if (namespacedKey == null) {
            return null;
        }
        return Registry.SOUNDS.get(namespacedKey);
    }

    private void sendStatus(CommandSender sender) {
        resetDailyCounterIfNeeded();

        StringJoiner windows = new StringJoiner(", ");
        for (TimeWindow window : oddHourWindows) {
            windows.add(window.toString());
        }

        sender.sendMessage(color("&8&m--------------------------------"));
        sender.sendMessage(color("&dCrestfall &7status"));
        sender.sendMessage(color("&7Enabled: &f" + getConfig().getBoolean("enabled", true)));
        sender.sendMessage(color("&7Loaded lines: &f" + announcements.size()));
        sender.sendMessage(color("&7Odd hour now: &f" + isOddHour()));
        sender.sendMessage(color("&7Windows: &f" + (windows.length() == 0 ? "none" : windows)));
        sender.sendMessage(color("&7Announcements today: &f" + announcementsToday));
        sender.sendMessage(color("&7PlaceholderAPI: &f" + (isPlaceholderApiEnabled() ? "enabled" : "not detected")));
        sender.sendMessage(color("&8&m--------------------------------"));
    }

    private void loadAnnouncements() {
        File file = new File(getDataFolder(), "announcements.txt");
        if (!file.exists()) {
            saveResourceIfMissing("announcements.txt");
        }

        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    announcements.add(trimmed);
                }
            }
        } catch (IOException exception) {
            getLogger().severe("Could not read announcements.txt: " + exception.getMessage());
        }
    }

    private void saveResourceIfMissing(String resourceName) {
        File target = new File(getDataFolder(), resourceName);
        if (!target.exists()) {
            saveResource(resourceName, false);
        }
    }

    private String randomAnnouncement() {
        return announcements.get(ThreadLocalRandom.current().nextInt(announcements.size()));
    }

    private boolean isOddHour() {
        if (oddHourWindows.isEmpty()) {
            return true;
        }

        LocalTime now = ZonedDateTime.now(zoneId).toLocalTime();
        for (TimeWindow window : oddHourWindows) {
            if (window.contains(now)) {
                return true;
            }
        }
        return false;
    }

    private void resetDailyCounterIfNeeded() {
        String today = ZonedDateTime.now(zoneId).format(DATE_FORMAT);
        if (!today.equals(currentDay)) {
            currentDay = today;
            announcementsToday = 0;
        }
    }

    private ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.systemDefault();
        }

        try {
            return ZoneId.of(value);
        } catch (Exception exception) {
            getLogger().warning("Invalid timezone '" + value + "', using server timezone.");
            return ZoneId.systemDefault();
        }
    }

    private Component renderMessage(String message, Player player) {
        String rendered = applyExternalPlaceholders(applyPlaceholders(message, player), player);
        return LEGACY_SERIALIZER.deserialize(normalizeHexColors(rendered));
    }

    private String plainText(String message, Player player) {
        return PlainTextComponentSerializer.plainText().serialize(renderMessage(message, player));
    }

    private String applyPlaceholders(String message, Player player) {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        Player randomPlayer = Bukkit.getOnlinePlayers().stream()
                .skip(ThreadLocalRandom.current().nextInt(Math.max(1, Bukkit.getOnlinePlayers().size())))
                .findFirst()
                .orElse(null);

        return message
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{time}", now.format(CLOCK_FORMAT))
                .replace("{date}", now.format(DATE_FORMAT))
                .replace("{world}", Bukkit.getWorlds().isEmpty() ? "unknown" : Bukkit.getWorlds().get(0).getName())
                .replace("{viewer}", player == null ? "console" : player.getName())
                .replace("{player}", randomPlayer == null ? "someone" : randomPlayer.getName());
    }

    private String applyExternalPlaceholders(String message, Player player) {
        if (player == null || !getConfig().getBoolean("placeholderapi.enabled", true) || !isPlaceholderApiEnabled()) {
            return message;
        }

        return PlaceholderAPI.setPlaceholders(player, message);
    }

    private boolean isPlaceholderApiEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    private String normalizeHexColors(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }

        message = normalizeCmiGradients(message);
        Matcher matcher = CMI_HEX_PATTERN.matcher(message);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(normalized, Matcher.quoteReplacement("&#" + matcher.group(1)));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private String normalizeCmiGradients(String message) {
        Matcher matcher = CMI_GRADIENT_PATTERN.matcher(message);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(applyGradient(matcher.group(2), matcher.group(1), matcher.group(3))));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private String applyGradient(String text, String startHex, String endHex) {
        int[] start = parseRgb(startHex);
        int[] end = parseRgb(endHex);
        int length = Math.max(1, text.length() - 1);
        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < text.length(); index++) {
            double ratio = length == 0 ? 0.0D : (double) index / length;
            int red = interpolate(start[0], end[0], ratio);
            int green = interpolate(start[1], end[1], ratio);
            int blue = interpolate(start[2], end[2], ratio);
            builder.append(String.format(Locale.ROOT, "&#%02x%02x%02x", red, green, blue));
            builder.append(text.charAt(index));
        }

        return builder.toString();
    }

    private int[] parseRgb(String hex) {
        return new int[] {
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    private int interpolate(int start, int end, double ratio) {
        return (int) Math.round(start + ((end - start) * ratio));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }

    private record TimeWindow(LocalTime start, LocalTime end) {

        static TimeWindow parse(String value) {
            if (value == null || !value.contains("-")) {
                return null;
            }

            String[] parts = value.split("-", 2);
            try {
                return new TimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
            } catch (DateTimeParseException exception) {
                return null;
            }
        }

        boolean contains(LocalTime time) {
            if (start.equals(end)) {
                return true;
            }

            if (start.isBefore(end)) {
                return !time.isBefore(start) && time.isBefore(end);
            }

            return !time.isBefore(start) || time.isBefore(end);
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }
}
