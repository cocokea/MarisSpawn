package vn.maris.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MarisSpawnPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int GUI_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final long AFK_TRIGGER_MILLIS = 60_000L;

    private final Map<String, Location> spawns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Location> warps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Location> afks = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<Player> teleporting = ConcurrentHashMap.newKeySet();
    private final Map<java.util.UUID, Long> lastMovement = new ConcurrentHashMap<>();
    private final Set<java.util.UUID> afkAutoTeleporting = ConcurrentHashMap.newKeySet();

    private File locationFile;
    private FileConfiguration locationConfig;
    private File messageFile;
    private FileConfiguration messages;
    private File spawnsFile;
    private FileConfiguration spawnsGuiConfig;
    private File afkGuiFile;
    private FileConfiguration afkGuiConfig;
    private String defaultSpawn = "";
    private Object afkTaskHandle;
    private boolean afkWatcherUsesFolia;

    @Override
    public void onEnable() {
        
        saveDefaultConfig();
        MarisPluginStartup.bootstrap(this, "cocokea/MarisSpawn");
loadPluginState();
        Bukkit.getPluginManager().registerEvents(this, this);
        register("spawn");
        register("warp");
        register("setspawn");
        register("setwarp");
        register("delwarp");
        register("afk");
        register("setafk");
        register("marispawn");
        startAfkWatcher();
    }

    @Override
    public void onDisable() {
        stopAfkWatcher();
        teleporting.clear();
        lastMovement.clear();
        afkAutoTeleporting.clear();
    }

    private void loadPluginState() {
        saveDefaultConfig();
        saveResourceIfMissing("message.yml");
        saveResourceIfMissing("location.yml");
        saveResourceIfMissing("spawns.yml");
        saveResourceIfMissing("guis/afk-areas.yml");

        locationFile = new File(getDataFolder(), "location.yml");
        migrateLocationFileBeforeLoad();
        locationConfig = YamlConfiguration.loadConfiguration(locationFile);
        messageFile = new File(getDataFolder(), "message.yml");
        messages = YamlConfiguration.loadConfiguration(messageFile);
        spawnsFile = new File(getDataFolder(), "spawns.yml");
        spawnsGuiConfig = YamlConfiguration.loadConfiguration(spawnsFile);
        afkGuiFile = new File(new File(getDataFolder(), "guis"), "afk-areas.yml");
        afkGuiConfig = YamlConfiguration.loadConfiguration(afkGuiFile);

        loadLocations();
    }

    private void reloadPluginState() {
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(messageFile);
        locationConfig = YamlConfiguration.loadConfiguration(locationFile);
        spawnsGuiConfig = YamlConfiguration.loadConfiguration(spawnsFile);
        afkGuiConfig = YamlConfiguration.loadConfiguration(afkGuiFile);
        loadLocations();
    }

    private void saveResourceIfMissing(String resourcePath) {
        File file = new File(getDataFolder(), resourcePath);
        if (!file.exists()) {
            saveResource(resourcePath, false);
        }
    }

    private void migrateLocationFileBeforeLoad() {
        if (locationFile == null || !locationFile.exists()) return;
        try {
            String original = Files.readString(locationFile.toPath(), StandardCharsets.UTF_8);
            String sanitized = original.replaceAll("(?m)^\\s*==\\s*:\\s*(?:org\\.bukkit\\.)?Location\\s*$\\R?", "");
            Matcher matcher = Pattern.compile("(?m)^(\\s*world\\s*:\\s*)(['\"]?)([^'\"\\r\\n#]+)(['\"]?)(\\s*(?:#.*)?)$").matcher(sanitized);
            StringBuffer migrated = new StringBuffer();
            boolean changed = !sanitized.equals(original);
            while (matcher.find()) {
                String worldName = matcher.group(3).trim();
                String normalized = normalizeLegacyWorldName(worldName);
                if (!worldName.equals(normalized)) changed = true;
                String replacement = matcher.group(1) + "'" + normalized.replace("'", "''") + "'" + matcher.group(5);
                matcher.appendReplacement(migrated, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(migrated);
            if (changed) {
                Files.writeString(locationFile.toPath(), migrated.toString(), StandardCharsets.UTF_8);
                getLogger().info("Migrated location.yml to safe plain-YAML locations for Canvas/Paper 26.1.2.");
            }
        } catch (IOException exception) {
            getLogger().warning("Could not migrate legacy world names in location.yml: " + exception.getMessage());
        }
    }

    private String normalizeLegacyWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) return worldName;
        String value = worldName.trim();
        if (value.contains("/")) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "world" -> "world/dimensions/minecraft/overworld";
            case "world_nether" -> "world/dimensions/minecraft/the_nether";
            case "world_the_end" -> "world/dimensions/minecraft/the_end";
            default -> "world/dimensions/worlds/" + value;
        };
    }

    private void register(String command) {
        Objects.requireNonNull(getCommand(command), command).setExecutor(this);
        Objects.requireNonNull(getCommand(command), command).setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            if ("marispawn".equals(name) && args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
                reloadPluginState();
                sender.sendMessage("MarisSpawn reloaded.");
                return true;
            }
            sender.sendMessage(plainMessage("only-player"));
            return true;
        }

        switch (name) {
            case "spawn" -> handleSpawn(player, args);
            case "warp" -> handleWarp(player, args);
            case "setspawn" -> handleSetSpawn(player, args);
            case "setwarp" -> handleSetWarp(player, args);
            case "delwarp" -> handleDelWarp(player, args);
            case "afk" -> handleAfk(player, args);
            case "setafk" -> handleSetAfk(player, args);
            case "marispawn" -> handleMarisSpawn(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleMarisSpawn(Player player, String[] args) {
        if (!hasAdmin(player)) return;
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            reloadPluginState();
            player.sendMessage(color("&aReloaded MarisSpawn."));
            return;
        }
        player.sendMessage(color("&cUsage: /marispawn reload"));
    }

    private void handleSpawn(Player player, String[] args) {
        if (args.length == 0) {
            openSpawnGui(player, 0);
            return;
        }
        String key = args[0];
        Location location = spawns.get(key);
        if (location == null) {
            send(player, "spawn-not-found", Map.of("spawn", key));
            return;
        }
        teleportWithCooldown(player, location, "spawn-success", Map.of("spawn", key));
    }

    private void handleWarp(Player player, String[] args) {
        if (args.length == 0) {
            send(player, "warp-usage", Map.of("destinations", destinations()));
            return;
        }
        String key = args[0];
        Location location = warps.get(key);
        if (location == null) {
            send(player, "warp-not-found", Map.of("warp", key, "destinations", destinations()));
            return;
        }
        teleportWithCooldown(player, location, "warp-success", Map.of("warp", key));
    }

    private void handleAfk(Player player, String[] args) {
        if (args.length == 0) {
            openAfkGui(player, 0);
            return;
        }
        String key = args[0];
        Location location = afks.get(key);
        if (location == null) {
            send(player, "afk-not-found", Map.of("afk", key));
            return;
        }
        teleportWithCooldown(player, location, "afk-success", Map.of("afk", key));
    }

    private void handleSetSpawn(Player player, String[] args) {
        if (!hasAdmin(player)) return;
        if (args.length == 0) {
            send(player, "setspawn-usage", Map.of());
            return;
        }
        String key = args[0];
        Location location = player.getLocation().clone();
        boolean shouldBecomeDefault = defaultSpawn == null || defaultSpawn.isBlank() || !spawns.containsKey(defaultSpawn);
        spawns.put(key, location);
        if (shouldBecomeDefault) {
            defaultSpawn = key;
            player.getWorld().setSpawnLocation(location);
        }
        saveLocations();
        send(player, "setspawn-success", Map.of("spawn", key));
    }

    private void handleSetWarp(Player player, String[] args) {
        if (!hasAdmin(player)) return;
        if (args.length == 0) {
            send(player, "setwarp-usage", Map.of());
            return;
        }
        String key = args[0];
        warps.put(key, player.getLocation().clone());
        saveLocations();
        send(player, "setwarp-success", Map.of("warp", key));
    }

    private void handleSetAfk(Player player, String[] args) {
        if (!hasAdmin(player)) return;
        if (args.length == 0) {
            send(player, "setafk-usage", Map.of());
            return;
        }
        String key = args[0];
        afks.put(key, player.getLocation().clone());
        saveLocations();
        send(player, "setafk-success", Map.of("afk", key));
    }

    private void handleDelWarp(Player player, String[] args) {
        if (!hasAdmin(player)) return;
        if (args.length == 0) {
            send(player, "delwarp-usage", Map.of("destinations", destinations()));
            return;
        }
        String key = args[0];
        if (warps.remove(key) == null) {
            send(player, "warp-not-found", Map.of("warp", key, "destinations", destinations()));
            return;
        }
        saveLocations();
        send(player, "delwarp-success", Map.of("warp", key));
    }

    private boolean hasAdmin(Player player) {
        if (player.hasPermission("marisspawn.admin")) return true;
        send(player, "no-permission", Map.of());
        return false;
    }

    private void openSpawnGui(Player player, int page) {
        openLocationGui(player, GuiType.SPAWN, spawns, spawnsGuiConfig, page);
    }

    private void openAfkGui(Player player, int page) {
        openLocationGui(player, GuiType.AFK, afks, afkGuiConfig, page);
    }

    private void openLocationGui(Player player, GuiType type, Map<String, Location> locations, FileConfiguration guiConfig, int page) {
        List<String> keys = new ArrayList<>(locations.keySet());
        int pageCount = Math.max(1, (int) Math.ceil(keys.size() / (double) PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        Inventory inventory = Bukkit.createInventory(new PagedHolder(type, safePage), GUI_SIZE, color(apply(guiConfig.getString("title", "&8gui"), Map.of("page", String.valueOf(safePage + 1)))));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, keys.size());
        for (int index = start; index < end; index++) {
            int slot = index - start;
            String key = keys.get(index);
            Location location = locations.get(key);
            inventory.setItem(slot, createLocationItem(guiConfig, type, key, location));
        }

        if (safePage > 0) {
            inventory.setItem(45, createSimpleItem(Material.ARROW, "&#00FF8Cʙᴀᴄᴋ", List.of("&fClick to go to the previous page")));
        }
        if (safePage + 1 < pageCount) {
            inventory.setItem(53, createSimpleItem(Material.ARROW, "&#00FF8Cɴᴇxᴛ", List.of("&fClick to go to the next page")));
        }

        if (type == GuiType.SPAWN) {
            inventory.setItem(49, createSimpleItem(Material.LIGHT_BLUE_GLAZED_TERRACOTTA, "&#00FF8Csᴘᴀᴡɴs", List.of("&fClick to teleport to a random spawn")));
        } else {
            inventory.setItem(49, createSimpleItem(Material.AMETHYST_BLOCK, "&#00FF8Cᴀғᴋ", List.of("&fClick to teleport to a random afk area")));
        }

        player.openInventory(inventory);
    }

    private ItemStack createLocationItem(FileConfiguration guiConfig, GuiType type, String key, Location location) {
        Material material = material(guiConfig.getString("item.material", "ITEM_FRAME"));
        int online = onlineInWorld(location == null ? null : location.getWorld());
        int max = guiConfig.getInt("item.max-online", 100);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put(type.placeholder, key);
        placeholders.put("name", key);
        placeholders.put("online_lobby", String.valueOf(online));
        placeholders.put("max_online", String.valueOf(max));

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(color(apply(guiConfig.getString("item.name", "&#00A2FF%name%"), placeholders))));
        List<Component> lore = new ArrayList<>();
        for (String line : guiConfig.getStringList("item.lore")) {
            lore.add(noItalic(color(apply(line, placeholders))));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSimpleItem(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(color(name)));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(noItalic(color(line)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof PagedHolder pagedHolder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;

        int slot = event.getRawSlot();
        if (slot == 45) {
            if (pagedHolder.page > 0) {
                openGui(player, pagedHolder.type, pagedHolder.page - 1);
            }
            return;
        }
        if (slot == 53) {
            Map<String, Location> source = sourceFor(pagedHolder.type);
            if ((pagedHolder.page + 1) * PAGE_SIZE < source.size()) {
                openGui(player, pagedHolder.type, pagedHolder.page + 1);
            }
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            if (pagedHolder.type == GuiType.SPAWN) {
                teleportRandomSpawn(player);
            } else {
                teleportRandomAfk(player);
            }
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;

        Map<String, Location> source = sourceFor(pagedHolder.type);
        List<String> keys = new ArrayList<>(source.keySet());
        int index = pagedHolder.page * PAGE_SIZE + slot;
        if (index < 0 || index >= keys.size()) return;
        String key = keys.get(index);
        Location location = source.get(key);
        if (location == null) return;
        player.closeInventory();
        if (pagedHolder.type == GuiType.AFK) {
            teleportWithCooldown(player, location, "afk-success", Map.of("afk", key));
        } else {
            teleportWithCooldown(player, location, "spawn-success", Map.of("spawn", key));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PagedHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFirstJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        markMovement(player);
        if (!player.hasPlayedBefore()) {
            Location location = getFirstJoinSpawn();
            if (location != null) {
                Location target = location.clone();
                player.getScheduler().runDelayed(this, task -> player.teleportAsync(target), null, 1L);
            }
        }
        player.getScheduler().runDelayed(this, task -> evaluateAfkMembership(player), null, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastMovement.remove(player.getUniqueId());
        afkAutoTeleporting.remove(player.getUniqueId());
        teleporting.remove(player);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) {
            return;
        }
        markMovement(event.getPlayer());
        evaluateAfkMembership(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        markMovement(player);
        player.getScheduler().runDelayed(this, task -> evaluateAfkMembership(player), null, 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        markMovement(player);
        player.getScheduler().runDelayed(this, task -> evaluateAfkMembership(player), null, 1L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        markMovement(player);
        player.getScheduler().runDelayed(this, task -> evaluateAfkMembership(player), null, 1L);
    }

    private void openGui(Player player, GuiType type, int page) {
        if (type == GuiType.SPAWN) {
            openSpawnGui(player, page);
        } else {
            openAfkGui(player, page);
        }
    }

    private Map<String, Location> sourceFor(GuiType type) {
        return type == GuiType.SPAWN ? spawns : afks;
    }

    private void teleportRandomSpawn(Player player) {
        teleportRandom(player, spawns, "spawn-success", "spawn");
    }

    private void teleportRandomAfk(Player player) {
        teleportRandom(player, afks, "afk-success", "afk");
    }

    private void teleportRandom(Player player, Map<String, Location> source, String successPath, String placeholderKey) {
        if (source.isEmpty()) return;
        List<String> keys = new ArrayList<>(source.keySet());
        Collections.shuffle(keys);
        String key = keys.get(0);
        Location location = source.get(key);
        if (location == null) return;
        teleportWithCooldown(player, location, successPath, Map.of(placeholderKey, key));
    }

    private Location getFirstJoinSpawn() {
        if (spawns.isEmpty()) return null;
        Location defaultLocation = defaultSpawn == null || defaultSpawn.isBlank() ? null : spawns.get(defaultSpawn);
        if (defaultLocation != null) return defaultLocation;
        return spawns.values().iterator().next();
    }

    private void teleportWithCooldown(Player player, Location target, String successPath, Map<String, String> placeholders) {
        if (teleporting.contains(player)) return;
        World targetWorld = target.getWorld();
        if (targetWorld == null) {
            send(player, "world-not-found", placeholders);
            return;
        }
        if (player.getWorld().equals(targetWorld)) {
            finishTeleport(player, target, successPath, placeholders);
            return;
        }
        teleporting.add(player);
        countdown(player, player.getLocation().clone(), target.clone(), successPath, placeholders, getConfig().getInt("teleport.cooldown-seconds", 5));
    }

    private void countdown(Player player, Location startLocation, Location target, String successPath, Map<String, String> placeholders, int secondsLeft) {
        if (!player.isOnline()) {
            teleporting.remove(player);
            return;
        }
        if (hasMovedBlock(player, startLocation)) {
            teleporting.remove(player);
            Component cancelled = color(messages.getString("teleport-cancelled-moved", "&cTeleport cancelled because you moved."));
            player.sendMessage(cancelled);
            player.sendActionBar(cancelled);
            return;
        }
        if (secondsLeft <= 0) {
            finishTeleport(player, target, successPath, placeholders);
            teleporting.remove(player);
            return;
        }
        Map<String, String> map = new HashMap<>(placeholders);
        map.put("cooldown", String.valueOf(secondsLeft));
        player.sendActionBar(color(apply(messages.getString("teleport-countdown", "&7Teleport in &#FFED00%cooldown%s"), map)));
        play(player, getConfig().getString("teleport.countdown-sound", "BLOCK_NOTE_BLOCK_HAT"),
                (float) getConfig().getDouble("teleport.countdown-volume", 1.0),
                (float) getConfig().getDouble("teleport.countdown-pitch", 1.2));
        player.getScheduler().runDelayed(this, task -> countdown(player, startLocation, target, successPath, placeholders, secondsLeft - 1), () -> teleporting.remove(player), 20L);
    }

    private boolean hasMovedBlock(Player player, Location startLocation) {
        Location current = player.getLocation();
        World startWorld = startLocation.getWorld();
        World currentWorld = current.getWorld();
        if (startWorld == null || currentWorld == null) return true;
        if (!startWorld.equals(currentWorld)) return true;
        return current.getBlockX() != startLocation.getBlockX()
                || current.getBlockY() != startLocation.getBlockY()
                || current.getBlockZ() != startLocation.getBlockZ();
    }

    private void finishTeleport(Player player, Location target, String successPath, Map<String, String> placeholders) {
        player.teleportAsync(target).thenAccept(success -> {
            if (!success || !player.isOnline()) return;
            player.getScheduler().runDelayed(this, task -> {
                markMovement(player);
                Component message = color(apply(messages.getString(successPath, "&aTeleported."), placeholders));
                player.sendMessage(message);
                player.sendActionBar(message);
                play(player, getConfig().getString("teleport.success-sound", "ENTITY_ENDERMAN_TELEPORT"), 1.0f, 1.0f);
            }, null, 1L);
        });
    }

    private int onlineInWorld(World world) {
        if (world == null) return 0;
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getWorld().equals(world)) count++;
        return count;
    }

    private String destinations() {
        return warps.isEmpty() ? messages.getString("no-destinations", "none") : String.join(", ", warps.keySet());
    }

    private void loadLocations() {
        spawns.clear();
        warps.clear();
        afks.clear();
        defaultSpawn = locationConfig.getString("default-spawn", "");
        loadSection("spawns", spawns);
        loadSection("warps", warps);
        loadSection("afks", afks);
        if ((defaultSpawn == null || defaultSpawn.isBlank() || !spawns.containsKey(defaultSpawn)) && !spawns.isEmpty()) {
            defaultSpawn = spawns.keySet().iterator().next();
        }
    }

    private void loadSection(String path, Map<String, Location> target) {
        ConfigurationSection section = locationConfig.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                Location location = readLocation(path + "." + key);
                if (location != null) target.put(key, location);
            } catch (RuntimeException exception) {
                getLogger().warning("Skipping invalid location '" + path + "." + key + "': " + exception.getMessage());
            }
        }
    }

    private Location readLocation(String path) {
        ConfigurationSection section = locationConfig.getConfigurationSection(path);
        if (section == null) return null;

        String worldName = section.getString("world", "");
        World world = findWorld(worldName);
        if (world == null) {
            getLogger().warning("Skipping location '" + path + "' because world is not loaded or does not exist: " + worldName);
            return null;
        }

        return new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    private void writeLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) return;
        locationConfig.set(path + ".world", location.getWorld().getName());
        locationConfig.set(path + ".x", location.getX());
        locationConfig.set(path + ".y", location.getY());
        locationConfig.set(path + ".z", location.getZ());
        locationConfig.set(path + ".yaw", location.getYaw());
        locationConfig.set(path + ".pitch", location.getPitch());
    }

    private World findWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return null;
        World direct = Bukkit.getWorld(worldName);
        if (direct != null) return direct;

        String normalized = normalizeLegacyWorldName(worldName);
        World migrated = Bukkit.getWorld(normalized);
        if (migrated != null) return migrated;

        String simple = worldName;
        int slash = simple.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < simple.length()) simple = simple.substring(slash + 1);
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            if (name.equalsIgnoreCase(worldName) || name.equalsIgnoreCase(normalized) || name.equalsIgnoreCase(simple) || name.endsWith("/" + simple)) {
                return world;
            }
        }
        return null;
    }

    private void saveLocations() {
        locationConfig.set("default-spawn", defaultSpawn);
        locationConfig.set("spawns", null);
        locationConfig.set("warps", null);
        locationConfig.set("afks", null);
        spawns.forEach((key, location) -> writeLocation("spawns." + key, location));
        warps.forEach((key, location) -> writeLocation("warps." + key, location));
        afks.forEach((key, location) -> writeLocation("afks." + key, location));
        try {
            locationConfig.save(locationFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save location.yml: " + exception.getMessage());
        }
    }

    private void send(Player player, String path, Map<String, String> placeholders) {
        List<String> list = messages.getStringList(path);
        if (!list.isEmpty()) {
            for (String line : list) player.sendMessage(color(apply(line, placeholders)));
            return;
        }
        player.sendMessage(color(apply(messages.getString(path, ""), placeholders)));
    }

    private String plainMessage(String path) {
        String value = messages == null ? "Only players can use this command." : messages.getString(path, "Only players can use this command.");
        return apply(value, Map.of());
    }

    private String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    private Component color(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        String mini = text.replace("&", "\u00A7");
        mini = translateHex(mini);
        mini = mini.replace("\u00A70", "<black>").replace("\u00A71", "<dark_blue>").replace("\u00A72", "<dark_green>")
                .replace("\u00A73", "<dark_aqua>").replace("\u00A74", "<dark_red>").replace("\u00A75", "<dark_purple>")
                .replace("\u00A76", "<gold>").replace("\u00A77", "<gray>").replace("\u00A78", "<dark_gray>")
                .replace("\u00A79", "<blue>").replace("\u00A7a", "<green>").replace("\u00A7b", "<aqua>")
                .replace("\u00A7c", "<red>").replace("\u00A7d", "<light_purple>").replace("\u00A7e", "<yellow>")
                .replace("\u00A7f", "<white>").replace("\u00A7l", "<bold>").replace("\u00A7n", "<underlined>")
                .replace("\u00A7o", "<italic>").replace("\u00A7m", "<strikethrough>").replace("\u00A7k", "<obfuscated>")
                .replace("\u00A7r", "<reset>");
        return MINI.deserialize(mini);
    }

    private Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private String translateHex(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i + 8 <= text.length() && text.charAt(i) == '\u00A7' && text.charAt(i + 1) == '#') {
                String hex = text.substring(i + 2, i + 8);
                if (hex.matches("[A-Fa-f0-9]{6}")) {
                    builder.append("<#").append(hex).append(">");
                    i += 7;
                    continue;
                }
            }
            builder.append(text.charAt(i));
        }
        return builder.toString();
    }

    private Material material(String name) {
        Material material = Material.matchMaterial(name == null ? "ITEM_FRAME" : name);
        return material == null ? Material.ITEM_FRAME : material;
    }

    private void play(Player player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isBlank()) return;
        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(toSoundKey(soundName)));
        if (sound != null) player.playSound(player.getLocation(), sound, volume, pitch);
        else getLogger().warning("Invalid sound in config.yml: " + soundName);
    }

    private String toSoundKey(String soundName) {
        String value = soundName.toLowerCase(Locale.ROOT).trim();
        if (value.startsWith("minecraft:")) value = value.substring("minecraft:".length());
        if (value.contains(".")) return value;
        return switch (value) {
            case "block_note_block_hat" -> "block.note_block.hat";
            case "block_note_block_pling" -> "block.note_block.pling";
            case "entity_enderman_teleport" -> "entity.enderman.teleport";
            default -> value.replace("_", ".");
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("marispawn".equals(name)) {
            if (args.length == 1) {
                return List.of("reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
        if (args.length != 1) return Collections.emptyList();
        String input = args[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "spawn" -> spawns.keySet().stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input)).collect(Collectors.toList());
            case "warp", "delwarp" -> warps.keySet().stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input)).collect(Collectors.toList());
            case "afk" -> afks.keySet().stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input)).collect(Collectors.toList());
            case "setspawn", "setwarp", "setafk" -> List.of("lobby");
            default -> Collections.emptyList();
        };
    }

    private void startAfkWatcher() {
        stopAfkWatcher();
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            Method runAtFixedRate = scheduler.getClass().getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class, long.class);
            afkTaskHandle = runAtFixedRate.invoke(scheduler, this, new AfkWatcherConsumer(this), 20L, 20L);
            afkWatcherUsesFolia = true;
        } catch (ReflectiveOperationException ignored) {
            afkTaskHandle = Bukkit.getScheduler().runTaskTimer(this, this::tickAfkWatcher, 20L, 20L);
            afkWatcherUsesFolia = false;
        }
    }

    private void stopAfkWatcher() {
        if (afkTaskHandle == null) return;
        try {
            if (afkWatcherUsesFolia) {
                afkTaskHandle.getClass().getMethod("cancel").invoke(afkTaskHandle);
            } else if (afkTaskHandle instanceof org.bukkit.scheduler.BukkitTask task) {
                task.cancel();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        afkTaskHandle = null;
    }

    private void tickAfkWatcher() {
        if (afks.isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isInForcedAfkWorld(player)) continue;

            long last = lastMovement.computeIfAbsent(player.getUniqueId(), uuid -> System.currentTimeMillis());
            if (System.currentTimeMillis() - last < AFK_TRIGGER_MILLIS) continue;
            if (!afkAutoTeleporting.add(player.getUniqueId())) continue;

            teleportPlayerToRandomAfk(player);
        }
    }

    private void teleportPlayerToRandomAfk(Player player) {
        if (afks.isEmpty()) {
            afkAutoTeleporting.remove(player.getUniqueId());
            return;
        }
        List<String> keys = new ArrayList<>(afks.keySet());
        Collections.shuffle(keys);
        String key = keys.get(0);
        Location location = afks.get(key);
        if (location == null) {
            afkAutoTeleporting.remove(player.getUniqueId());
            return;
        }
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) {
                afkAutoTeleporting.remove(player.getUniqueId());
                return;
            }
            player.performCommand("afk " + key);
            player.getScheduler().runDelayed(this, delayedTask -> {
                afkAutoTeleporting.remove(player.getUniqueId());
                markMovement(player);
                evaluateAfkMembership(player);
            }, () -> afkAutoTeleporting.remove(player.getUniqueId()), 2L);
        }, () -> afkAutoTeleporting.remove(player.getUniqueId()));
    }

    private boolean isInForcedAfkWorld(Player player) {
        World world = player.getWorld();
        return world != null && "spawn".equalsIgnoreCase(world.getName());
    }

    private void markMovement(Player player) {
        lastMovement.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void evaluateAfkMembership(Player player) {
        if (!player.isOnline()) return;
        if (isInForcedAfkWorld(player)) {
            lastMovement.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    private enum GuiType {
        SPAWN("spawn"),
        AFK("afk");

        private final String placeholder;

        GuiType(String placeholder) {
            this.placeholder = placeholder;
        }
    }

    private static final class PagedHolder implements InventoryHolder {
        private final GuiType type;
        private final int page;

        private PagedHolder(GuiType type, int page) {
            this.type = type;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, GUI_SIZE, Component.text("MarisSpawn"));
        }
    }

    private static final class AfkWatcherConsumer implements java.util.function.Consumer<Object> {
        private final MarisSpawnPlugin plugin;

        private AfkWatcherConsumer(MarisSpawnPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void accept(Object ignored) {
            plugin.tickAfkWatcher();
        }
    }
}
