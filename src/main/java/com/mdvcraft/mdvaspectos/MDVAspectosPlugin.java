package com.mdvcraft.mdvaspectos;

import me.clip.placeholderapi.PlaceholderAPI;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.InputDataResult;
import net.skinsrestorer.api.property.SkinProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MDVAspectosPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<String, Catalog> catalogs = new HashMap<>();
    private final Map<String, Catalog> aliases = new HashMap<>();
    private final Map<String, MenuButton> globalButtons = new HashMap<>();
    private final Set<String> noRaceClasses = new HashSet<>();
    private final Map<String, SkinTexture> textureCache = new ConcurrentHashMap<>();
    private final Map<UUID, RememberedSkin> rememberedSkins = new ConcurrentHashMap<>();

    private File skinDataFile;
    private YamlConfiguration skinData;

    private NamespacedKey skinKey;
    private NamespacedKey buttonKey;
    private SkinsRestorer skinsRestorer;

    private String menuTitle;
    private int menuSize;
    private String classPlaceholder;
    private String applyCommand;
    private boolean useSkinsRestorerHeads;
    private boolean fillEmptySlots;
    private Material fillMaterial;
    private String fillName;

    private String prefix;

    private boolean skinMemoryEnabled;
    private boolean skinMemorySaveFromMenu;
    private boolean skinMemoryListenConsole;
    private boolean skinMemoryListenPlayerCommands;
    private boolean skinMemoryPlayerCommandRequirePermission;
    private String skinMemoryPlayerCommandSavePermission;
    private List<String> skinMemoryPlayerCommandRequiredPermissions = Collections.emptyList();
    private int skinMemoryPlayerCommandSaveDelayTicks;
    private boolean skinMemoryApplyOnJoin;
    private boolean skinMemoryOnlyConfiguredSkins;
    private boolean skinMemoryRequireCatalogMatch;
    private boolean skinMemoryDebug;
    private String skinMemoryApplyCommand;
    private int skinMemoryApplyDelaySeconds;
    private List<Integer> skinMemoryRetryDelaySeconds = Collections.emptyList();

    private boolean raceCommandGateEnabled;
    private String raceCommandGateRequiredPermission;
    private String raceCommandGateBypassPermission;
    private boolean raceCommandGateAllowOps;
    private String raceCommandGateRedirectCommand;
    private int raceCommandGateRedirectDelayTicks;
    private boolean raceCommandGateMessageEnabled;
    private List<String> raceCommandGateMessageLines = Collections.emptyList();
    private Set<String> raceCommandGateAllowedCommands = Collections.emptySet();
    private boolean raceCommandGateDebug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.skinDataFile = new File(getDataFolder(), "skin-memory.yml");
        this.skinKey = new NamespacedKey(this, "skin_entry");
        this.buttonKey = new NamespacedKey(this, "menu_button");

        try {
            this.skinsRestorer = SkinsRestorerProvider.get();
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo obtener SkinsRestorer API. Las cabezas usaran texture-value manual o fallback.");
            this.skinsRestorer = null;
        }

        loadConfiguration();
        Objects.requireNonNull(getCommand("aspecto")).setExecutor(this);
        Objects.requireNonNull(getCommand("aspecto")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MDVAspectos habilitado con " + catalogs.size() + " catalogos.");
    }

    private void loadConfiguration() {
        reloadConfig();
        textureCache.clear();
        catalogs.clear();
        aliases.clear();
        globalButtons.clear();
        noRaceClasses.clear();

        menuTitle = color(getConfig().getString("settings.menu-title", "&8Aspectos: {race_display}"));
        menuSize = normalizeInventorySize(getConfig().getInt("settings.menu-size", 54));
        classPlaceholder = getConfig().getString("settings.class-placeholder", "%mmocore_class_id%");
        applyCommand = getConfig().getString("settings.apply-command", "skin set {skin} {player}");
        useSkinsRestorerHeads = getConfig().getBoolean("settings.use-skinsrestorer-heads", true);
        fillEmptySlots = getConfig().getBoolean("settings.fill-empty-slots", true);
        fillName = color(getConfig().getString("settings.fill-name", " "));
        prefix = color(getConfig().getString("messages.prefix", "&6&l[&5&lMDVCRAFT&6&l]&4> &r"));

        loadSkinMemorySettings();
        loadRaceCommandGateSettings();

        String fillMatName = getConfig().getString("settings.fill-material", "BLACK_STAINED_GLASS_PANE");
        fillMaterial = Material.matchMaterial(fillMatName == null ? "BLACK_STAINED_GLASS_PANE" : fillMatName);
        if (fillMaterial == null) fillMaterial = Material.BLACK_STAINED_GLASS_PANE;

        for (String raw : getConfig().getStringList("settings.no-race-classes")) {
            noRaceClasses.add(normalize(raw));
        }

        loadButtons(getConfig().getConfigurationSection("buttons"), globalButtons, "buttons");

        ConfigurationSection catalogsSection = getConfig().getConfigurationSection("catalogs");
        if (catalogsSection == null) {
            getLogger().warning("No hay catalogos configurados en config.yml");
            return;
        }

        for (String catalogKey : catalogsSection.getKeys(false)) {
            ConfigurationSection section = catalogsSection.getConfigurationSection(catalogKey);
            if (section == null) continue;

            String display = color(section.getString("display", catalogKey));
            Catalog catalog = new Catalog(catalogKey, display, normalizeInventorySize(section.getInt("size", menuSize)));

            List<String> aliasList = section.getStringList("aliases");
            aliasList.add(catalogKey);
            for (String alias : aliasList) {
                catalog.aliases.add(normalize(alias));
            }

            loadButtons(section.getConfigurationSection("buttons"), catalog.buttons, "catalogs." + catalogKey + ".buttons");

            ConfigurationSection skinsSection = section.getConfigurationSection("skins");
            if (skinsSection != null) {
                for (String skinEntryKey : skinsSection.getKeys(false)) {
                    ConfigurationSection skinSection = skinsSection.getConfigurationSection(skinEntryKey);
                    if (skinSection == null) continue;

                    int slot = skinSection.getInt("slot", -1);
                    String skinName = skinSection.getString("skin", skinEntryKey);
                    String name = color(skinSection.getString("name", "&e" + skinName));
                    List<String> lore = colorList(skinSection.getStringList("lore"));
                    String command = skinSection.getString("command", null);
                    String textureValue = emptyToNull(skinSection.getString("texture-value", null));
                    String textureSignature = emptyToNull(skinSection.getString("texture-signature", null));

                    SkinEntry entry = new SkinEntry(skinEntryKey, skinName, slot, name, lore, command, textureValue, textureSignature);
                    catalog.skins.put(skinEntryKey, entry);
                }
            }

            catalogs.put(catalogKey, catalog);
            for (String alias : catalog.aliases) aliases.put(alias, catalog);
        }

        loadRememberedSkins();
    }


    private void loadSkinMemorySettings() {
        skinMemoryEnabled = getConfig().getBoolean("skin-memory.enabled", true);
        skinMemorySaveFromMenu = getConfig().getBoolean("skin-memory.save-from-menu", true);
        skinMemoryListenConsole = getConfig().getBoolean("skin-memory.listen-console-skin-commands", true);
        skinMemoryListenPlayerCommands = getConfig().getBoolean("skin-memory.listen-player-skin-commands", false);
        skinMemoryPlayerCommandRequirePermission = getConfig().getBoolean("skin-memory.player-command-require-permission", true);
        skinMemoryPlayerCommandSavePermission = getConfig().getString("skin-memory.player-command-save-permission", "mdvaspectos.skinmemory.free");
        skinMemoryPlayerCommandSaveDelayTicks = Math.max(0, getConfig().getInt("skin-memory.player-command-save-delay-ticks", 20));

        List<String> requiredPermissions = new ArrayList<>(getConfig().getStringList("skin-memory.player-command-required-permissions"));
        if (requiredPermissions.isEmpty() && skinMemoryPlayerCommandSavePermission != null && !skinMemoryPlayerCommandSavePermission.isBlank()) {
            requiredPermissions.add(skinMemoryPlayerCommandSavePermission);
        }
        skinMemoryPlayerCommandRequiredPermissions = requiredPermissions;

        skinMemoryApplyOnJoin = getConfig().getBoolean("skin-memory.apply-on-join", true);
        skinMemoryOnlyConfiguredSkins = getConfig().getBoolean("skin-memory.only-configured-skins", true);
        skinMemoryRequireCatalogMatch = getConfig().getBoolean("skin-memory.require-current-catalog-match", true);
        skinMemoryDebug = getConfig().getBoolean("skin-memory.debug", false);
        skinMemoryApplyCommand = getConfig().getString("skin-memory.apply-command", applyCommand == null ? "skin set {skin} {player}" : applyCommand);
        skinMemoryApplyDelaySeconds = Math.max(0, getConfig().getInt("skin-memory.apply-delay-seconds", 8));

        List<Integer> delays = new ArrayList<>();
        for (Integer value : getConfig().getIntegerList("skin-memory.retry-delays-seconds")) {
            if (value != null && value >= 0) delays.add(value);
        }
        skinMemoryRetryDelaySeconds = delays;
    }

    private void loadRaceCommandGateSettings() {
        raceCommandGateEnabled = getConfig().getBoolean("race-command-gate.enabled", false);
        raceCommandGateRequiredPermission = getConfig().getString("race-command-gate.required-permission", "mdvcraft.race.selected");
        raceCommandGateBypassPermission = getConfig().getString("race-command-gate.bypass-permission", "mdvcraft.racegate.bypass");
        raceCommandGateAllowOps = getConfig().getBoolean("race-command-gate.allow-ops", true);
        raceCommandGateRedirectCommand = getConfig().getString("race-command-gate.redirect-command", "raza");
        raceCommandGateRedirectDelayTicks = Math.max(0, getConfig().getInt("race-command-gate.redirect-delay-ticks", 2));
        raceCommandGateMessageEnabled = getConfig().getBoolean("race-command-gate.message.enabled", true);
        raceCommandGateMessageLines = colorList(getConfig().getStringList("race-command-gate.message.text"));
        raceCommandGateDebug = getConfig().getBoolean("race-command-gate.debug", false);

        Set<String> allowed = new HashSet<>();
        List<String> configured = getConfig().getStringList("race-command-gate.allowed-commands");
        if (configured.isEmpty()) {
            configured = List.of("raza", "clase", "class", "mmocore:class", "login", "l", "register", "reg", "authme:login", "authme:register");
        }
        for (String command : configured) {
            String normalized = normalizeCommandName(command);
            if (!normalized.isBlank()) allowed.add(normalized);
        }
        String redirect = normalizeCommandName(raceCommandGateRedirectCommand);
        if (!redirect.isBlank()) allowed.add(redirect);
        raceCommandGateAllowedCommands = allowed;
    }

    private void loadRememberedSkins() {
        rememberedSkins.clear();
        if (skinDataFile == null) skinDataFile = new File(getDataFolder(), "skin-memory.yml");
        skinData = YamlConfiguration.loadConfiguration(skinDataFile);

        ConfigurationSection players = skinData.getConfigurationSection("players");
        if (players == null) return;

        for (String uuidKey : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidKey);
                ConfigurationSection sec = players.getConfigurationSection(uuidKey);
                if (sec == null) continue;
                String skin = sec.getString("skin", "");
                if (skin == null || skin.isBlank()) continue;
                String playerName = sec.getString("name", "");
                String catalog = sec.getString("catalog", "");
                String source = sec.getString("source", "data");
                long updatedAt = sec.getLong("updated-at", 0L);
                rememberedSkins.put(uuid, new RememberedSkin(uuid, playerName, skin, catalog, source, updatedAt));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("UUID invalido en skin-memory.yml: " + uuidKey);
            }
        }
    }

    private void saveRememberedSkins() {
        if (skinDataFile == null) skinDataFile = new File(getDataFolder(), "skin-memory.yml");
        YamlConfiguration out = new YamlConfiguration();
        out.set("version", 1);
        for (RememberedSkin remembered : rememberedSkins.values()) {
            String base = "players." + remembered.uuid;
            out.set(base + ".name", remembered.playerName);
            out.set(base + ".skin", remembered.skinName);
            out.set(base + ".catalog", remembered.catalogKey);
            out.set(base + ".source", remembered.source);
            out.set(base + ".updated-at", remembered.updatedAt);
        }
        try {
            out.save(skinDataFile);
            skinData = out;
        } catch (IOException exception) {
            getLogger().warning("No se pudo guardar skin-memory.yml: " + exception.getMessage());
        }
    }

    private void rememberSkin(Player player, Catalog catalog, SkinEntry entry, String source) {
        if (player == null || catalog == null || entry == null) return;
        rememberSkin(player, entry.skinName, catalog.key, source);
    }

    private void rememberSkin(Player player, String skinName, String catalogKey, String source) {
        if (!skinMemoryEnabled || player == null || skinName == null || skinName.isBlank()) return;
        if (skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(skinName)) {
            debugSkinMemory("No guardo skin no configurada: " + skinName + " para " + player.getName());
            return;
        }

        String resolvedCatalog = catalogKey == null || catalogKey.isBlank() ? findCatalogKeyForSkin(skinName) : catalogKey;
        RememberedSkin remembered = new RememberedSkin(
                player.getUniqueId(),
                player.getName(),
                skinName,
                resolvedCatalog == null ? "" : resolvedCatalog,
                source == null ? "unknown" : source,
                System.currentTimeMillis()
        );
        rememberedSkins.put(player.getUniqueId(), remembered);
        saveRememberedSkins();
        debugSkinMemory("Skin guardada: " + player.getName() + " -> " + skinName + " (" + remembered.catalogKey + ", " + remembered.source + ")");
    }

    private boolean isConfiguredSkin(String skinName) {
        return findCatalogKeyForSkin(skinName) != null;
    }

    private String findCatalogKeyForSkin(String skinName) {
        if (skinName == null) return null;
        for (Catalog catalog : catalogs.values()) {
            for (SkinEntry entry : catalog.skins.values()) {
                if (entry.skinName.equalsIgnoreCase(skinName) || entry.key.equalsIgnoreCase(skinName)) return catalog.key;
            }
        }
        return null;
    }

    private RememberedSkin getRememberedSkin(Player player) {
        if (player == null) return null;
        return rememberedSkins.get(player.getUniqueId());
    }

    private void scheduleRememberedSkinApply(Player player) {
        if (!skinMemoryEnabled || !skinMemoryApplyOnJoin || player == null) return;
        RememberedSkin remembered = getRememberedSkin(player);
        if (remembered == null || remembered.skinName == null || remembered.skinName.isBlank()) return;

        List<Integer> delays = new ArrayList<>();
        delays.add(skinMemoryApplyDelaySeconds);
        delays.addAll(skinMemoryRetryDelaySeconds);

        for (Integer delay : delays) {
            int safeDelay = delay == null ? 0 : Math.max(0, delay);
            Bukkit.getScheduler().runTaskLater(this, () -> applyRememberedSkin(player.getUniqueId()), safeDelay * 20L);
        }
    }

    private void applyRememberedSkin(UUID uuid) {
        if (!skinMemoryEnabled || uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        RememberedSkin remembered = rememberedSkins.get(uuid);
        if (remembered == null || remembered.skinName == null || remembered.skinName.isBlank()) return;

        if (skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(remembered.skinName)) {
            debugSkinMemory("No reaplico skin no configurada: " + remembered.skinName + " para " + player.getName());
            return;
        }

        if (skinMemoryRequireCatalogMatch) {
            Catalog currentCatalog = resolvePlayerCatalog(player);
            if (currentCatalog == null) {
                debugSkinMemory("No pude validar raza/catalogo actual de " + player.getName() + "; no reaplico aun.");
                return;
            }
            if (remembered.catalogKey != null && !remembered.catalogKey.isBlank() && !currentCatalog.key.equalsIgnoreCase(remembered.catalogKey)) {
                debugSkinMemory("No reaplico " + remembered.skinName + " a " + player.getName() + " porque su catalogo actual es " + currentCatalog.key + " y el guardado es " + remembered.catalogKey + ".");
                return;
            }
        }

        String cmd = skinMemoryApplyCommand == null || skinMemoryApplyCommand.isBlank() ? "skin set {skin} {player}" : skinMemoryApplyCommand;
        cmd = cmd.replace("{player}", player.getName())
                .replace("{skin}", remembered.skinName)
                .replace("{catalog}", remembered.catalogKey == null ? "" : remembered.catalogKey);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        debugSkinMemory("Reaplicando skin: " + cmd);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private Catalog resolvePlayerCatalog(Player player) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        try {
            String raceRaw = PlaceholderAPI.setPlaceholders(player, classPlaceholder == null ? "%mmocore_class_id%" : classPlaceholder);
            return aliases.get(normalize(raceRaw));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void debugSkinMemory(String message) {
        if (skinMemoryDebug) getLogger().info("[SkinMemory] " + message);
    }

    private void loadButtons(ConfigurationSection buttonsSection, Map<String, MenuButton> output, String path) {
        if (buttonsSection == null) return;
        for (String key : buttonsSection.getKeys(false)) {
            ConfigurationSection sec = buttonsSection.getConfigurationSection(key);
            if (sec == null || !sec.getBoolean("enabled", true)) continue;
            int slot = sec.getInt("slot", -1);
            if (slot < 0 || slot >= 54) {
                getLogger().warning("Slot invalido en " + path + "." + key + ": " + slot);
                continue;
            }
            List<String> commands = new ArrayList<>(sec.getStringList("commands"));
            String singleCommand = sec.getString("command", "");
            if (commands.isEmpty() && singleCommand != null && !singleCommand.isBlank()) commands.add(singleCommand);
            MenuButton button = new MenuButton(
                    key,
                    slot,
                    sec.getString("material", "PAPER"),
                    Math.max(1, Math.min(64, sec.getInt("amount", 1))),
                    color(sec.getString("name", sec.getString("display", ""))),
                    colorList(sec.getStringList("lore")),
                    sec.getString("head-owner", ""),
                    readTexture(sec),
                    commands,
                    sec.getBoolean("close-on-click", true),
                    sec.getBoolean("console", false) || sec.getString("run-as", "player").equalsIgnoreCase("console")
            );
            output.put(key, button);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mdvaspectos.reload")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            loadConfiguration();
            sender.sendMessage(message("reloaded"));
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("reaplicar") || args[0].equalsIgnoreCase("aplicar"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("only-player"));
                return true;
            }
            if (!player.hasPermission("mdvaspectos.use")) {
                player.sendMessage(message("no-permission"));
                return true;
            }
            RememberedSkin remembered = getRememberedSkin(player);
            if (remembered == null) {
                player.sendMessage(message("skin-memory-empty"));
                return true;
            }
            applyRememberedSkin(player.getUniqueId());
            player.sendMessage(message("skin-memory-reapplied").replace("{skin}", remembered.skinName));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("only-player"));
            return true;
        }

        if (!player.hasPermission("mdvaspectos.use")) {
            player.sendMessage(message("no-permission"));
            return true;
        }

        openAspectMenu(player);
        return true;
    }

    private void openAspectMenu(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            player.sendMessage(message("no-placeholderapi"));
            return;
        }

        String raceRaw = PlaceholderAPI.setPlaceholders(player, classPlaceholder == null ? "%mmocore_class_id%" : classPlaceholder);
        String race = normalize(raceRaw);
        if (race.startsWith("%") || noRaceClasses.contains(race)) {
            player.sendMessage(message("no-race"));
            return;
        }

        Catalog catalog = aliases.get(race);
        if (catalog == null) {
            player.sendMessage(message("no-catalog"));
            return;
        }

        String title = menuTitle.replace("{race}", catalog.key).replace("{race_display}", catalog.display);
        Inventory inventory = Bukkit.createInventory(new AspectMenuHolder(catalog.key), catalog.size, title);

        if (fillEmptySlots) fillInventory(inventory);

        for (SkinEntry entry : catalog.skins.values()) {
            if (entry.slot < 0 || entry.slot >= inventory.getSize()) {
                getLogger().warning("Slot invalido para skin " + entry.key + " en catalogo " + catalog.key + ": " + entry.slot);
                continue;
            }
            inventory.setItem(entry.slot, createSkinItem(entry));
        }

        Map<String, MenuButton> visibleButtons = new HashMap<>(globalButtons);
        visibleButtons.putAll(catalog.buttons);
        for (MenuButton button : visibleButtons.values()) {
            if (button.slot < 0 || button.slot >= inventory.getSize()) {
                getLogger().warning("Slot invalido para boton " + button.key + " en catalogo " + catalog.key + ": " + button.slot);
                continue;
            }
            inventory.setItem(button.slot, createButtonItem(button, player, catalog));
        }

        player.openInventory(inventory);
    }

    private void fillInventory(Inventory inventory) {
        ItemStack filler = new ItemStack(fillMaterial);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(fillName);
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    private ItemStack createSkinItem(SkinEntry entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(entry.name);
        meta.setLore(entry.lore);
        setSkinTexture(meta, entry);
        meta.getPersistentDataContainer().set(skinKey, PersistentDataType.STRING, entry.key);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createButtonItem(MenuButton button, Player player, Catalog catalog) {
        Material material = Material.matchMaterial(button.material == null ? "PAPER" : button.material.toUpperCase(Locale.ROOT));
        if (material == null) material = Material.PAPER;
        ItemStack item = new ItemStack(material, button.amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull) {
            String texture = applyButtonPlaceholders(button.texture, player, catalog);
            if (texture != null && !texture.isBlank()) {
                applyCustomHeadTexture(skull, texture);
            } else if (button.headOwner != null && !button.headOwner.isBlank()) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(applyButtonPlaceholders(button.headOwner, player, catalog)));
            }
            meta = skull;
        }

        if (button.name != null && !button.name.isBlank()) meta.setDisplayName(applyButtonPlaceholders(button.name, player, catalog));
        List<String> lore = new ArrayList<>();
        for (String line : button.lore) lore.add(applyButtonPlaceholders(line, player, catalog));
        if (!lore.isEmpty()) meta.setLore(lore);
        meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.STRING, button.key);
        item.setItemMeta(meta);
        return item;
    }

    private String readTexture(ConfigurationSection sec) {
        if (sec == null) return "";
        String texture = sec.getString("custom-head-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("head-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("skull-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("skull_texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("texture-base64", "");
        return texture == null ? "" : texture.trim();
    }

    private String extractTextureUrl(String textureValue) {
        if (textureValue == null) return "";
        String value = textureValue.trim();
        if (value.isBlank()) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            int urlKey = decoded.indexOf("\"url\"");
            if (urlKey < 0) return "";
            int colon = decoded.indexOf(':', urlKey);
            if (colon < 0) return "";
            int firstQuote = decoded.indexOf('"', colon);
            if (firstQuote < 0) return "";
            int secondQuote = decoded.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) return "";
            return decoded.substring(firstQuote + 1, secondQuote).replace("\\/", "/");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void applyCustomHeadTexture(SkullMeta skull, String textureValue) {
        String textureUrl = extractTextureUrl(textureValue);
        if (textureUrl == null || textureUrl.isBlank()) return;
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "MDVAspectos");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(textureUrl));
            profile.setTextures(textures);
            skull.setOwnerProfile(profile);
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo aplicar textura custom de boton: " + throwable.getClass().getSimpleName() + " - " + throwable.getMessage());
        }
    }

    private void setSkinTexture(SkullMeta meta, SkinEntry entry) {
        SkinTexture texture = null;

        if (entry.textureValue != null) {
            texture = new SkinTexture(entry.textureValue, entry.textureSignature);
        } else if (useSkinsRestorerHeads && skinsRestorer != null) {
            texture = textureCache.computeIfAbsent(entry.skinName.toLowerCase(Locale.ROOT), ignored -> findSkinRestorerTexture(entry.skinName));
        }

        if (texture != null && texture.value != null && !texture.value.isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), entry.skinName.length() > 16 ? null : entry.skinName);
                if (applyTextureProperty(profile, texture)) {
                    meta.setOwnerProfile(profile);
                    return;
                }
            } catch (Throwable throwable) {
                getLogger().warning("No se pudo aplicar textura de head para " + entry.skinName + ": " + throwable.getMessage());
            }
        }

        try {
            // Fallback. Solo se vera bien si skinName es un nombre premium o si el cliente lo resuelve.
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.skinName));
        } catch (Throwable ignored) {
            // Si falla, queda como cabeza default.
        }
    }

    /**
     * Aplica la propiedad "textures" sin depender de una clase ProfileProperty concreta en compilacion.
     * En algunas APIs de Paper/Spigot la clase esta en paquetes distintos o no existe como tipo publico.
     */
    private boolean applyTextureProperty(PlayerProfile profile, SkinTexture texture) {
        String value = texture.value;
        String signature = texture.signature;

        String[] possibleClasses = {
                "com.destroystokyo.paper.profile.ProfileProperty",
                "org.bukkit.profile.ProfileProperty"
        };

        for (String className : possibleClasses) {
            try {
                Class<?> propertyClass = Class.forName(className);
                Object property;
                if (signature != null && !signature.isBlank()) {
                    property = propertyClass
                            .getConstructor(String.class, String.class, String.class)
                            .newInstance("textures", value, signature);
                } else {
                    property = propertyClass
                            .getConstructor(String.class, String.class)
                            .newInstance("textures", value);
                }

                profile.getClass().getMethod("setProperty", propertyClass).invoke(profile, property);
                return true;
            } catch (ClassNotFoundException ignored) {
                // Probar siguiente paquete.
            } catch (Throwable throwable) {
                getLogger().fine("No se pudo aplicar ProfileProperty usando " + className + ": " + throwable.getMessage());
            }
        }

        return false;
    }

    private SkinTexture findSkinRestorerTexture(String skinName) {
        try {
            Optional<InputDataResult> result = skinsRestorer.getSkinStorage().findSkinData(skinName);
            if (result.isEmpty()) {
                // Si no existe en cache, intenta encontrar/crear. Para tus skins custom guardadas normalmente no hace falta.
                result = skinsRestorer.getSkinStorage().findOrCreateSkinData(skinName);
            }
            if (result.isPresent()) {
                SkinProperty property = result.get().getProperty();
                return new SkinTexture(property.getValue(), property.getSignature());
            }
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo obtener textura de SkinsRestorer para '" + skinName + "': " + throwable.getMessage());
        }
        return SkinTexture.EMPTY;
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleRememberedSkinApply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRaceCommandGate(PlayerCommandPreprocessEvent event) {
        if (!raceCommandGateEnabled) return;
        Player player = event.getPlayer();
        if (player == null) return;
        if (hasRaceCommandGateAccess(player)) return;

        String commandName = extractCommandName(event.getMessage());
        if (commandName.isBlank()) return;
        if (raceCommandGateAllowedCommands.contains(commandName)) return;

        event.setCancelled(true);
        debugRaceCommandGate("Bloqueado /" + commandName + " para " + player.getName());

        if (raceCommandGateMessageEnabled) {
            if (raceCommandGateMessageLines == null || raceCommandGateMessageLines.isEmpty()) {
                player.sendMessage(prefix + color("&cDebes elegir una raza antes de usar comandos. Usa &e/raza&c."));
            } else {
                for (String line : raceCommandGateMessageLines) player.sendMessage(line);
            }
        }

        String redirect = raceCommandGateRedirectCommand == null ? "raza" : raceCommandGateRedirectCommand.trim();
        if (redirect.startsWith("/")) redirect = redirect.substring(1);
        if (redirect.isBlank()) return;
        String finalRedirect = redirect;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) Bukkit.dispatchCommand(player, finalRedirect);
        }, Math.max(0, raceCommandGateRedirectDelayTicks));
    }

    private boolean hasRaceCommandGateAccess(Player player) {
        if (player == null) return true;
        if (raceCommandGateAllowOps && player.isOp()) return true;
        if (raceCommandGateBypassPermission != null && !raceCommandGateBypassPermission.isBlank() && player.hasPermission(raceCommandGateBypassPermission)) return true;
        return raceCommandGateRequiredPermission == null || raceCommandGateRequiredPermission.isBlank() || player.hasPermission(raceCommandGateRequiredPermission);
    }

    private void debugRaceCommandGate(String message) {
        if (raceCommandGateDebug) getLogger().info("[RaceCommandGate] " + message);
    }

    private static String extractCommandName(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return "";
        String commandLine = rawMessage.trim();
        if (commandLine.startsWith("/")) commandLine = commandLine.substring(1);
        if (commandLine.isBlank()) return "";
        String[] parts = commandLine.split("\\s+");
        if (parts.length == 0) return "";
        return normalizeCommandName(parts[0]);
    }

    private static String normalizeCommandName(String input) {
        if (input == null) return "";
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!skinMemoryEnabled || !skinMemoryListenPlayerCommands) return;
        Player player = event.getPlayer();
        if (player == null) return;

        String skinName = parsePlayerSkinCommand(event.getMessage());
        if (skinName == null || skinName.isBlank()) return;

        if (skinMemoryPlayerCommandRequirePermission && !hasAllSkinMemoryPlayerPermissions(player)) {
            debugSkinMemory("Comando /skin de jugador ignorado por falta de permiso: " + player.getName());
            return;
        }
        if (!isSafeSkinCommandArgument(skinName)) {
            debugSkinMemory("Comando /skin de jugador ignorado por argumento inseguro: " + skinName);
            return;
        }

        if (skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(skinName)) {
            debugSkinMemory("Comando /skin de jugador ignorado por no estar en catalogo: " + skinName);
            return;
        }

        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        int delay = Math.max(0, skinMemoryPlayerCommandSaveDelayTicks);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) return;
            rememberSkin(online, skinName, findCatalogKeyForSkin(skinName), "player-command");
            debugSkinMemory("Skin guardada desde comando de jugador: " + playerName + " -> " + skinName);
        }, delay);
    }

    private boolean hasAllSkinMemoryPlayerPermissions(Player player) {
        if (player == null) return false;
        if (skinMemoryPlayerCommandRequiredPermissions == null || skinMemoryPlayerCommandRequiredPermissions.isEmpty()) return true;
        for (String permission : skinMemoryPlayerCommandRequiredPermissions) {
            if (permission == null || permission.isBlank()) continue;
            if (!player.hasPermission(permission)) return false;
        }
        return true;
    }

    private String parsePlayerSkinCommand(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return null;
        String commandLine = rawMessage.trim();
        if (commandLine.startsWith("/")) commandLine = commandLine.substring(1);
        String[] parts = commandLine.split("\\s+");
        if (parts.length < 2) return null;

        String commandName = parts[0].toLowerCase(Locale.ROOT);
        if (commandName.contains(":")) commandName = commandName.substring(commandName.indexOf(':') + 1);
        if (!commandName.equals("skin")) return null;

        String firstArg = parts[1].toLowerCase(Locale.ROOT);
        if (firstArg.equals("set")) {
            if (parts.length < 3) return null;
            return parts[2];
        }

        if (isNonSkinSubcommand(firstArg)) return null;
        return parts[1];
    }

    private boolean isNonSkinSubcommand(String value) {
        if (value == null) return true;
        return value.equals("help")
                || value.equals("?")
                || value.equals("clear")
                || value.equals("reset")
                || value.equals("update")
                || value.equals("url")
                || value.equals("search")
                || value.equals("gui")
                || value.equals("menu")
                || value.equals("reload");
    }

    private boolean isSafeSkinCommandArgument(String skinName) {
        if (skinName == null) return false;
        String trimmed = skinName.trim();
        if (trimmed.length() < 2 || trimmed.length() > 64) return false;
        return trimmed.matches("[A-Za-z0-9_\\-]+(?:\\.[A-Za-z0-9_\\-]+)?");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!skinMemoryEnabled || !skinMemoryListenConsole) return;
        String raw = event.getCommand();
        if (raw == null || raw.isBlank()) return;
        String commandLine = raw.trim();
        if (commandLine.startsWith("/")) commandLine = commandLine.substring(1);
        String[] parts = commandLine.split("\\s+");
        if (parts.length < 4) return;
        if (!parts[0].equalsIgnoreCase("skin")) return;
        if (!parts[1].equalsIgnoreCase("set")) return;

        String skinName = parts[2];
        String playerName = parts[3];
        if (skinName == null || skinName.isBlank() || playerName == null || playerName.isBlank()) return;
        if (!isSafeSkinCommandArgument(skinName)) {
            debugSkinMemory("Comando skin de consola ignorado por argumento inseguro: " + skinName);
            return;
        }

        if (skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(skinName)) {
            debugSkinMemory("Comando skin ignorado por no estar en catalogo: " + skinName);
            return;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            debugSkinMemory("Comando skin ignorado porque el jugador no esta online: " + playerName);
            return;
        }

        rememberSkin(target, skinName, findCatalogKeyForSkin(skinName), "console-command");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AspectMenuHolder holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Catalog catalog = catalogs.get(holder.catalogKey);
        if (catalog == null) return;

        String buttonEntryKey = pdc.get(buttonKey, PersistentDataType.STRING);
        if (buttonEntryKey != null) {
            MenuButton button = catalog.buttons.get(buttonEntryKey);
            if (button == null) button = globalButtons.get(buttonEntryKey);
            if (button != null) runButtonCommands(player, catalog, button);
            return;
        }

        String skinEntryKey = pdc.get(skinKey, PersistentDataType.STRING);
        if (skinEntryKey == null) return;

        SkinEntry entry = catalog.skins.get(skinEntryKey);
        if (entry == null) return;

        // Revalidar la raza al hacer click para que nadie pueda mantener un menu viejo abierto.
        String raceRaw = PlaceholderAPI.setPlaceholders(player, classPlaceholder == null ? "%mmocore_class_id%" : classPlaceholder);
        Catalog currentCatalog = aliases.get(normalize(raceRaw));
        if (currentCatalog == null || !currentCatalog.key.equals(catalog.key)) {
            player.closeInventory();
            player.sendMessage(message("no-catalog"));
            return;
        }

        String cmd = entry.command != null && !entry.command.isBlank() ? entry.command : applyCommand;
        cmd = cmd.replace("{player}", player.getName())
                .replace("{skin}", entry.skinName)
                .replace("{catalog}", catalog.key)
                .replace("{race_display}", stripColor(catalog.display))
                .replace("{skin_display}", stripColor(entry.name));

        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        player.closeInventory();
        if (ok) {
            if (skinMemoryEnabled && skinMemorySaveFromMenu) rememberSkin(player, catalog, entry, "menu");
            player.sendMessage(message("skin-applied")
                    .replace("{skin}", entry.skinName)
                    .replace("{skin_display}", entry.name));
        } else {
            player.sendMessage(message("skin-error"));
        }
    }

    private void runButtonCommands(Player player, Catalog catalog, MenuButton button) {
        Runnable task = () -> {
            if (button.closeOnClick) player.closeInventory();
            for (String raw : button.commands) {
                String cmd = applyButtonPlaceholders(raw, player, catalog).trim();
                if (cmd.isBlank()) continue;
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (button.console) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                else Bukkit.dispatchCommand(player, cmd);
            }
        };
        Bukkit.getScheduler().runTask(this, task);
    }

    private String applyButtonPlaceholders(String input, Player player, Catalog catalog) {
        if (input == null) return "";
        String out = input
                .replace("{player}", player.getName())
                .replace("{catalog}", catalog.key)
                .replace("{race}", catalog.key)
                .replace("{race_display}", stripColor(catalog.display));
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { out = PlaceholderAPI.setPlaceholders(player, out); } catch (Throwable ignored) { }
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String current = args[0].toLowerCase(Locale.ROOT);
            if (sender.hasPermission("mdvaspectos.reload") && "reload".startsWith(current)) options.add("reload");
            if (sender.hasPermission("mdvaspectos.use")) {
                if ("reaplicar".startsWith(current)) options.add("reaplicar");
            }
            return options;
        }
        return Collections.emptyList();
    }

    private String message(String key) {
        return prefix + color(getConfig().getString("messages." + key, "&cMensaje no configurado: " + key));
    }

    private static String color(String input) {
        if (input == null) return "";
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private static List<String> colorList(List<String> input) {
        List<String> out = new ArrayList<>();
        for (String line : input) out.add(color(line));
        return out;
    }

    private static String stripColor(String input) {
        return ChatColor.stripColor(color(input));
    }

    private static String normalize(String input) {
        if (input == null) return "";
        String stripped = ChatColor.stripColor(color(input));
        if (stripped == null) return "";
        return stripped.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private static String emptyToNull(String input) {
        if (input == null || input.isBlank()) return null;
        return input;
    }

    private static int normalizeInventorySize(int size) {
        if (size < 9) return 9;
        if (size > 54) return 54;
        return ((size + 8) / 9) * 9;
    }

    private static final class Catalog {
        private final String key;
        private final String display;
        private final int size;
        private final Set<String> aliases = new HashSet<>();
        private final Map<String, SkinEntry> skins = new HashMap<>();
        private final Map<String, MenuButton> buttons = new HashMap<>();

        private Catalog(String key, String display, int size) {
            this.key = key;
            this.display = display;
            this.size = size;
        }
    }

    private static final class MenuButton {
        private final String key;
        private final int slot;
        private final String material;
        private final int amount;
        private final String name;
        private final List<String> lore;
        private final String headOwner;
        private final String texture;
        private final List<String> commands;
        private final boolean closeOnClick;
        private final boolean console;

        private MenuButton(String key, int slot, String material, int amount, String name, List<String> lore, String headOwner, String texture, List<String> commands, boolean closeOnClick, boolean console) {
            this.key = key;
            this.slot = slot;
            this.material = material == null ? "PAPER" : material;
            this.amount = amount;
            this.name = name == null ? "" : name;
            this.lore = lore == null ? Collections.emptyList() : lore;
            this.headOwner = headOwner == null ? "" : headOwner;
            this.texture = texture == null ? "" : texture;
            this.commands = commands == null ? Collections.emptyList() : commands;
            this.closeOnClick = closeOnClick;
            this.console = console;
        }
    }

    private static final class SkinEntry {
        private final String key;
        private final String skinName;
        private final int slot;
        private final String name;
        private final List<String> lore;
        private final String command;
        private final String textureValue;
        private final String textureSignature;

        private SkinEntry(String key, String skinName, int slot, String name, List<String> lore, String command, String textureValue, String textureSignature) {
            this.key = key;
            this.skinName = skinName;
            this.slot = slot;
            this.name = name;
            this.lore = lore;
            this.command = command;
            this.textureValue = textureValue;
            this.textureSignature = textureSignature;
        }
    }


    private static final class RememberedSkin {
        private final UUID uuid;
        private final String playerName;
        private final String skinName;
        private final String catalogKey;
        private final String source;
        private final long updatedAt;

        private RememberedSkin(UUID uuid, String playerName, String skinName, String catalogKey, String source, long updatedAt) {
            this.uuid = uuid;
            this.playerName = playerName == null ? "" : playerName;
            this.skinName = skinName == null ? "" : skinName;
            this.catalogKey = catalogKey == null ? "" : catalogKey;
            this.source = source == null ? "unknown" : source;
            this.updatedAt = updatedAt;
        }
    }

    private static final class SkinTexture {
        private static final SkinTexture EMPTY = new SkinTexture(null, null);
        private final String value;
        private final String signature;

        private SkinTexture(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }

    private static final class AspectMenuHolder implements InventoryHolder {
        private final String catalogKey;

        private AspectMenuHolder(String catalogKey) {
            this.catalogKey = catalogKey;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
