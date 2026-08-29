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
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.geysermc.geyser.api.GeyserApi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    private File bedrockMenuFile;
    private YamlConfiguration bedrockMenu;
    private boolean bedrockMenuEnabled;
    private String bedrockMenuTitle;
    private int bedrockMenuSize;
    private boolean bedrockFillEmptySlots;
    private Material bedrockFillMaterial;
    private String bedrockFillName;
    private boolean geyserSkullSyncEnabled;
    private String geyserMappingFileName;
    private boolean geyserSyncDebug;

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
    private long raceCommandGateMessageCooldownMillis;
    private boolean raceCommandGateInteractionsEnabled;
    private boolean raceCommandGateBlockSigns;
    private boolean raceCommandGateBlockItems;
    private final Map<String, GateBlockedItem> raceCommandGateBlockedItems = new HashMap<>();
    private final Map<UUID, Long> raceGateLastNoticeAt = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.skinDataFile = new File(getDataFolder(), "skin-memory.yml");
        this.bedrockMenuFile = new File(getDataFolder(), "MenusBedrock/aspectos.yml");
        ensureBedrockMenuFile();
        this.skinKey = new NamespacedKey(this, "skin_entry");
        this.buttonKey = new NamespacedKey(this, "menu_button");

        try {
            this.skinsRestorer = SkinsRestorerProvider.get();
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo obtener SkinsRestorer API. Las cabezas usaran texture-value manual o fallback.");
            this.skinsRestorer = null;
        }

        loadConfiguration();
        Bukkit.getScheduler().runTaskLater(this, this::syncGeyserSkullMappings, 20L);
        Objects.requireNonNull(getCommand("aspecto")).setExecutor(this);
        Objects.requireNonNull(getCommand("aspecto")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MDVAspectos habilitado con " + catalogs.size() + " catalogos.");
    }

    private void loadConfiguration() {
        reloadConfig();
        loadBedrockMenuConfiguration();
        textureCache.clear();
        catalogs.clear();
        aliases.clear();
        globalButtons.clear();
        noRaceClasses.clear();
        raceCommandGateBlockedItems.clear();

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


    private void ensureBedrockMenuFile() {
        try {
            if (bedrockMenuFile.getParentFile() != null && !bedrockMenuFile.getParentFile().exists()) {
                bedrockMenuFile.getParentFile().mkdirs();
            }
            if (!bedrockMenuFile.exists()) saveResource("MenusBedrock/aspectos.yml", false);
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo crear MenusBedrock/aspectos.yml: " + throwable.getMessage());
        }
    }

    private void loadBedrockMenuConfiguration() {
        ensureBedrockMenuFile();
        bedrockMenu = YamlConfiguration.loadConfiguration(bedrockMenuFile);
        bedrockMenuEnabled = bedrockMenu.getBoolean("settings.enabled", true);
        bedrockMenuTitle = color(bedrockMenu.getString("settings.title", "&8Aspectos: {race_display}"));
        bedrockMenuSize = normalizeInventorySize(bedrockMenu.getInt("settings.size", 27));
        bedrockFillEmptySlots = bedrockMenu.getBoolean("settings.fill-empty-slots", true);
        String matName = bedrockMenu.getString("settings.fill-material", "BLACK_STAINED_GLASS_PANE");
        bedrockFillMaterial = Material.matchMaterial(matName == null ? "BLACK_STAINED_GLASS_PANE" : matName);
        if (bedrockFillMaterial == null) bedrockFillMaterial = Material.BLACK_STAINED_GLASS_PANE;
        bedrockFillName = color(bedrockMenu.getString("settings.fill-name", " "));

        geyserSkullSyncEnabled = bedrockMenu.getBoolean("geyser-skulls.enabled", true);
        geyserMappingFileName = bedrockMenu.getString("geyser-skulls.mapping-file", "mdvaspectos_skulls.json");
        if (geyserMappingFileName == null || geyserMappingFileName.isBlank()) geyserMappingFileName = "mdvaspectos_skulls.json";
        if (!geyserMappingFileName.toLowerCase(Locale.ROOT).endsWith(".json")) geyserMappingFileName += ".json";
        geyserSyncDebug = bedrockMenu.getBoolean("geyser-skulls.debug", false);
    }

    /**
     * Genera el mapping oficial de custom skulls de Geyser. Geyser carga estos mappings
     * al iniciar; por eso, si el archivo cambia, el log pide reiniciar Geyser/servidor.
     */
    private boolean syncGeyserSkullMappings() {
        if (!geyserSkullSyncEnabled) return false;
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                getLogger().warning("Geyser API no esta disponible; no se genero el mapping de cabezas Bedrock.");
                return false;
            }

            LinkedHashSet<String> usernames = new LinkedHashSet<>();
            LinkedHashSet<String> profiles = new LinkedHashSet<>();
            LinkedHashSet<String> skinHashes = new LinkedHashSet<>();

            for (Catalog catalog : catalogs.values()) {
                for (SkinEntry entry : catalog.skins.values()) collectSkinEntryForGeyser(entry, profiles, skinHashes);
                for (MenuButton button : catalog.buttons.values()) collectButtonForGeyser(button, usernames, profiles, skinHashes);
            }
            for (MenuButton button : globalButtons.values()) collectButtonForGeyser(button, usernames, profiles, skinHashes);

            String json = buildGeyserSkullsJson(usernames, profiles, skinHashes);
            Path mappingsDir = api.configDirectory().resolve("custom_mappings");
            Files.createDirectories(mappingsDir);
            Path target = mappingsDir.resolve(geyserMappingFileName);
            String previous = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : null;
            if (json.equals(previous)) {
                if (geyserSyncDebug) getLogger().info("[Bedrock] Mapping de skulls sin cambios: " + target);
                return false;
            }
            Files.writeString(target, json, StandardCharsets.UTF_8);
            getLogger().warning("[Bedrock] Mapping de cabezas actualizado: " + target);
            getLogger().warning("[Bedrock] Reinicia Geyser o el servidor para que las cabezas custom aparezcan en inventarios Bedrock. /geyser reload tambien funciona, pero expulsa a los jugadores Bedrock conectados.");
            getLogger().info("[Bedrock] Registradas " + profiles.size() + " texturas de perfil, " + skinHashes.size() + " hashes y " + usernames.size() + " usernames.");
            return true;
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo sincronizar custom skulls con Geyser: " + throwable.getClass().getSimpleName() + " - " + throwable.getMessage());
            return false;
        }
    }

    private void collectSkinEntryForGeyser(SkinEntry entry, Set<String> profiles, Set<String> skinHashes) {
        if (entry == null) return;
        if (entry.textureValue != null && !entry.textureValue.isBlank()) {
            collectTextureForGeyser(entry.textureValue, profiles, skinHashes);
            return;
        }
        if (useSkinsRestorerHeads && skinsRestorer != null) {
            SkinTexture texture = textureCache.computeIfAbsent(entry.skinName.toLowerCase(Locale.ROOT), ignored -> findSkinRestorerTexture(entry.skinName));
            if (texture != null && texture.value != null && !texture.value.isBlank()) profiles.add(texture.value);
        }
    }

    private void collectButtonForGeyser(MenuButton button, Set<String> usernames, Set<String> profiles, Set<String> skinHashes) {
        if (button == null || button.material == null || !button.material.equalsIgnoreCase("PLAYER_HEAD")) return;
        if (button.texture != null && !button.texture.isBlank()) collectTextureForGeyser(button.texture, profiles, skinHashes);
        else if (button.headOwner != null && !button.headOwner.isBlank() && !button.headOwner.contains("{") && !button.headOwner.contains("%")) usernames.add(button.headOwner);
    }

    private void collectTextureForGeyser(String value, Set<String> profiles, Set<String> skinHashes) {
        if (value == null || value.isBlank()) return;
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            String hash = extractMinecraftTextureHash(trimmed);
            if (!hash.isBlank()) skinHashes.add(hash);
            return;
        }
        profiles.add(trimmed);
    }

    private String extractMinecraftTextureHash(String url) {
        if (url == null) return "";
        String marker = "/texture/";
        int index = url.indexOf(marker);
        if (index < 0) return "";
        String hash = url.substring(index + marker.length());
        int slash = hash.indexOf('/');
        if (slash >= 0) hash = hash.substring(0, slash);
        int query = hash.indexOf('?');
        if (query >= 0) hash = hash.substring(0, query);
        return hash.trim();
    }

    private String buildGeyserSkullsJson(Set<String> usernames, Set<String> profiles, Set<String> hashes) {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n  \"format_version\": 1,\n  \"skulls\": {\n");
        appendJsonArray(out, "username", usernames, 4);
        out.append(",\n");
        appendJsonArray(out, "profile", profiles, 4);
        out.append(",\n");
        appendJsonArray(out, "skin_hash", hashes, 4);
        out.append("\n  }\n}\n");
        return out.toString();
    }

    private void appendJsonArray(StringBuilder out, String key, Set<String> values, int indent) {
        String pad = " ".repeat(indent);
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        out.append(pad).append('\"').append(key).append("\": [");
        if (!sorted.isEmpty()) out.append('\n');
        for (int i = 0; i < sorted.size(); i++) {
            out.append(pad).append("  \"").append(jsonEscape(sorted.get(i))).append('\"');
            if (i + 1 < sorted.size()) out.append(',');
            out.append('\n');
        }
        if (!sorted.isEmpty()) out.append(pad);
        out.append(']');
    }

    private String jsonEscape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
        raceCommandGateMessageCooldownMillis = Math.max(0L, getConfig().getLong("race-command-gate.message-cooldown-ms", 1500L));
        raceCommandGateInteractionsEnabled = getConfig().getBoolean("race-command-gate.interactions.enabled", true);
        raceCommandGateBlockSigns = getConfig().getBoolean("race-command-gate.interactions.block-signs", true);
        raceCommandGateBlockItems = getConfig().getBoolean("race-command-gate.interactions.block-items", true);
        loadRaceCommandGateBlockedItems();

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

    private void loadRaceCommandGateBlockedItems() {
        raceCommandGateBlockedItems.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("race-command-gate.interactions.blocked-items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null || !itemSection.getBoolean("enabled", true)) continue;

            String materialName = itemSection.getString("material", "");
            Material material = null;
            if (materialName != null && !materialName.isBlank()) {
                material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
                if (material == null) {
                    getLogger().warning("Material invalido en race-command-gate.interactions.blocked-items." + key + ": " + materialName);
                    continue;
                }
            }

            int slot = itemSection.getInt("slot", -1);
            String nameContains = normalizePlain(itemSection.getString("name-contains", ""));
            raceCommandGateBlockedItems.put(key, new GateBlockedItem(key, material, slot, nameContains));
        }
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
                String state = sec.getString("state", "skin");
                boolean nativeSkin = state != null && (state.equalsIgnoreCase("native") || state.equalsIgnoreCase("clear") || state.equalsIgnoreCase("cleared"));
                String skin = sec.getString("skin", "");
                if (!nativeSkin && (skin == null || skin.isBlank())) continue;
                String playerName = sec.getString("name", "");
                String catalog = sec.getString("catalog", "");
                String source = sec.getString("source", "data");
                long updatedAt = sec.getLong("updated-at", 0L);
                rememberedSkins.put(uuid, new RememberedSkin(uuid, playerName, skin, catalog, source, updatedAt, nativeSkin));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("UUID invalido en skin-memory.yml: " + uuidKey);
            }
        }
    }

    private void saveRememberedSkins() {
        if (skinDataFile == null) skinDataFile = new File(getDataFolder(), "skin-memory.yml");
        YamlConfiguration out = new YamlConfiguration();
        out.set("version", 2);
        for (RememberedSkin remembered : rememberedSkins.values()) {
            String base = "players." + remembered.uuid;
            out.set(base + ".name", remembered.playerName);
            out.set(base + ".state", remembered.nativeSkin ? "native" : "skin");
            out.set(base + ".skin", remembered.nativeSkin ? "" : remembered.skinName);
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
                System.currentTimeMillis(),
                false
        );
        rememberedSkins.put(player.getUniqueId(), remembered);
        saveRememberedSkins();
        debugSkinMemory("Skin guardada: " + player.getName() + " -> " + skinName + " (" + remembered.catalogKey + ", " + remembered.source + ")");
    }

    private void rememberNativeSkin(Player player, String source) {
        if (!skinMemoryEnabled || player == null) return;
        RememberedSkin remembered = new RememberedSkin(
                player.getUniqueId(),
                player.getName(),
                "",
                "",
                source == null ? "skin-clear" : source,
                System.currentTimeMillis(),
                true
        );
        rememberedSkins.put(player.getUniqueId(), remembered);
        saveRememberedSkins();
        debugSkinMemory("Skin nativa guardada: " + player.getName() + " (" + remembered.source + ")");
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
        if (remembered == null) return;
        if (remembered.nativeSkin) {
            debugSkinMemory("No reaplico skin a " + player.getName() + " porque tiene guardado estado de skin nativa.");
            return;
        }
        if (remembered.skinName == null || remembered.skinName.isBlank()) return;

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
        if (remembered == null) return;
        if (remembered.nativeSkin) {
            debugSkinMemory("No reaplico skin a " + player.getName() + " porque tiene guardado estado de skin nativa.");
            return;
        }
        if (remembered.skinName == null || remembered.skinName.isBlank()) return;

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
            Bukkit.getScheduler().runTaskLater(this, this::syncGeyserSkullMappings, 1L);
            sender.sendMessage(message("reloaded"));
            return true;
        }

        if (args.length > 0 && isRememberSkinSubcommand(args[0])) {
            return handleRememberSkinCommand(sender, args, false);
        }

        if (args.length > 0 && isApplySkinSubcommand(args[0])) {
            return handleRememberSkinCommand(sender, args, true);
        }

        if (args.length > 0 && isNativeSkinSubcommand(args[0])) {
            return handleNativeSkinCommand(sender, args);
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("geysersync") || args[0].equalsIgnoreCase("syncgeyser"))) {
            if (!sender.hasPermission("mdvaspectos.reload")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            boolean changed = syncGeyserSkullMappings();
            sender.sendMessage(color(changed
                    ? "&aMapeo de cabezas Bedrock actualizado. &eReinicia Geyser/servidor para aplicarlo."
                    : "&aEl mapeo de cabezas Bedrock ya estaba actualizado."));
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
            if (remembered.nativeSkin) {
                player.sendMessage(message("skin-memory-native"));
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

    private boolean isRememberSkinSubcommand(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("recordar") || v.equals("recordarskin") || v.equals("guardar") || v.equals("guardarskin") || v.equals("remember") || v.equals("rememberskin");
    }

    private boolean isApplySkinSubcommand(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("aplicarskin") || v.equals("applyskin") || v.equals("setskin") || v.equals("set");
    }

    private boolean isNativeSkinSubcommand(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("native") || v.equals("nativa") || v.equals("clearskin") || v.equals("guardarnativa");
    }

    private boolean handleRememberSkinCommand(CommandSender sender, String[] args, boolean applyAlso) {
        if (!sender.hasPermission("mdvaspectos.skinmemory.admin") && !sender.hasPermission("mdvaspectos.reload")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(color("&cUso: /mdvaspectos " + args[0] + " <jugador> <skin>"));
            return true;
        }
        String targetName = args[1];
        String skinName = args[2];
        if (!isSafeSkinCommandArgument(targetName) || !isSafeSkinCommandArgument(skinName)) {
            sender.sendMessage(color("&cJugador o skin invalido."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(color("&cEl jugador debe estar conectado para guardar/aplicar la skin."));
            return true;
        }
        if (skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(skinName)) {
            sender.sendMessage(color("&cEsa skin no esta configurada en el catalogo."));
            return true;
        }

        if (applyAlso) {
            String cmd = "skin set " + skinName + " " + target.getName();
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            if (!ok) {
                sender.sendMessage(color("&cNo se pudo ejecutar: " + cmd));
                return true;
            }
        }

        rememberSkin(target, skinName, findCatalogKeyForSkin(skinName), applyAlso ? "mdvaspectos-applyskin" : "mdvaspectos-remember");
        sender.sendMessage(color("&aSkin guardada para &e" + target.getName() + "&a: &e" + skinName));
        return true;
    }

    private boolean handleNativeSkinCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mdvaspectos.skinmemory.admin") && !sender.hasPermission("mdvaspectos.reload")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&cUso: /mdvaspectos " + args[0] + " <jugador>"));
            return true;
        }
        String targetName = args[1];
        if (!isSafeSkinCommandArgument(targetName)) {
            sender.sendMessage(color("&cJugador invalido."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(color("&cEl jugador debe estar conectado para guardar skin nativa."));
            return true;
        }
        rememberNativeSkin(target, "mdvaspectos-native-command");
        sender.sendMessage(color("&aSkin nativa guardada para &e" + target.getName() + "&a."));
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

        if (bedrockMenuEnabled && isBedrockPlayer(player)) {
            openBedrockAspectMenu(player, catalog);
        } else {
            openJavaAspectMenu(player, catalog);
        }
    }

    /** Java conserva exactamente la disposicion y comportamiento historico. */
    private void openJavaAspectMenu(Player player, Catalog catalog) {
        String title = menuTitle.replace("{race}", catalog.key).replace("{race_display}", catalog.display);
        Inventory inventory = Bukkit.createInventory(new AspectMenuHolder(catalog.key, false), catalog.size, title);

        if (fillEmptySlots) fillInventory(inventory, fillMaterial, fillName);

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

    /**
     * Bedrock sigue usando un inventario real, pero con menos filas, objetivos tactiles grandes
     * y slots independientes de Java. Las cabezas siguen siendo PLAYER_HEAD: Geyser las
     * traduce gracias al archivo de custom skull mappings generado por este plugin.
     */
    private void openBedrockAspectMenu(Player player, Catalog catalog) {
        String title = bedrockMenuTitle.replace("{race}", catalog.key).replace("{race_display}", catalog.display);
        Inventory inventory = Bukkit.createInventory(new AspectMenuHolder(catalog.key, true), bedrockMenuSize, title);

        if (bedrockFillEmptySlots) fillInventory(inventory, bedrockFillMaterial, bedrockFillName);

        List<SkinEntry> entries = new ArrayList<>(catalog.skins.values());
        entries.sort((a, b) -> {
            int bySlot = Integer.compare(a.slot, b.slot);
            return bySlot != 0 ? bySlot : a.key.compareToIgnoreCase(b.key);
        });
        List<Integer> slots = resolveBedrockSkinSlots(catalog, entries.size());

        for (int i = 0; i < entries.size(); i++) {
            SkinEntry entry = entries.get(i);
            int slot = resolveBedrockSkinSlot(catalog, entry, i, slots);
            if (slot < 0 || slot >= inventory.getSize()) {
                getLogger().warning("Slot Bedrock invalido para skin " + entry.key + " en catalogo " + catalog.key + ": " + slot);
                continue;
            }
            inventory.setItem(slot, createBedrockSkinItem(entry, catalog));
        }

        Map<String, MenuButton> visibleButtons = new HashMap<>(globalButtons);
        visibleButtons.putAll(catalog.buttons);
        for (MenuButton button : visibleButtons.values()) {
            MenuButton bedrockButton = resolveBedrockButton(button, catalog);
            if (bedrockButton == null) continue;
            if (bedrockButton.slot < 0 || bedrockButton.slot >= inventory.getSize()) {
                getLogger().warning("Slot Bedrock invalido para boton " + button.key + " en catalogo " + catalog.key + ": " + bedrockButton.slot);
                continue;
            }
            inventory.setItem(bedrockButton.slot, createButtonItem(bedrockButton, player, catalog));
        }

        player.openInventory(inventory);
    }

    private boolean isBedrockPlayer(Player player) {
        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.isBedrockPlayer(player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ItemStack createBedrockSkinItem(SkinEntry entry, Catalog catalog) {
        ItemStack item = createSkinItem(entry);
        ConfigurationSection sec = bedrockMenu == null ? null : bedrockMenu.getConfigurationSection("catalogs." + catalog.key + ".skins." + entry.key);
        if (sec == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String name = sec.getString("name", "");
        if (name != null && !name.isBlank()) meta.setDisplayName(color(name));
        if (sec.isList("lore")) meta.setLore(colorList(sec.getStringList("lore")));
        item.setItemMeta(meta);
        return item;
    }

    private List<Integer> resolveBedrockSkinSlots(Catalog catalog, int count) {
        if (bedrockMenu != null) {
            List<Integer> catalogSlots = bedrockMenu.getIntegerList("catalogs." + catalog.key + ".skin-slots");
            if (!catalogSlots.isEmpty()) return catalogSlots;
            List<Integer> layout = bedrockMenu.getIntegerList("layouts." + Math.max(1, Math.min(9, count)));
            if (!layout.isEmpty()) return layout;
        }
        return switch (count) {
            case 1 -> List.of(13);
            case 2 -> List.of(12, 14);
            case 3 -> List.of(11, 13, 15);
            case 4 -> List.of(11, 12, 14, 15);
            case 5 -> List.of(10, 11, 12, 13, 14);
            default -> List.of(10, 11, 12, 13, 14, 15, 16);
        };
    }

    private int resolveBedrockSkinSlot(Catalog catalog, SkinEntry entry, int index, List<Integer> slots) {
        if (bedrockMenu != null) {
            String path = "catalogs." + catalog.key + ".skins." + entry.key + ".slot";
            if (bedrockMenu.contains(path)) return bedrockMenu.getInt(path);
        }
        if (index < slots.size()) return slots.get(index);
        return -1;
    }

    private MenuButton resolveBedrockButton(MenuButton base, Catalog catalog) {
        if (bedrockMenu == null) return base;
        ConfigurationSection sec = bedrockMenu.getConfigurationSection("catalogs." + catalog.key + ".buttons." + base.key);
        if (sec == null) sec = bedrockMenu.getConfigurationSection("buttons." + base.key);
        if (sec == null) return base;
        if (!sec.getBoolean("enabled", true)) return null;
        return new MenuButton(
                base.key,
                sec.getInt("slot", base.slot),
                sec.getString("material", base.material),
                Math.max(1, Math.min(64, sec.getInt("amount", base.amount))),
                color(sec.getString("name", base.name)),
                sec.isList("lore") ? colorList(sec.getStringList("lore")) : base.lore,
                sec.getString("head-owner", base.headOwner),
                readTexture(sec).isBlank() ? base.texture : readTexture(sec),
                sec.isList("commands") ? new ArrayList<>(sec.getStringList("commands")) : base.commands,
                sec.getBoolean("close-on-click", base.closeOnClick),
                sec.getBoolean("console", base.console) || sec.getString("run-as", base.console ? "console" : "player").equalsIgnoreCase("console")
        );
    }

    private void fillInventory(Inventory inventory, Material material, String displayName) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
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
        handleRaceCommandGateBlock(player, "/" + commandName);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onRaceInteractionGate(PlayerInteractEvent event) {
        if (!raceCommandGateEnabled || !raceCommandGateInteractionsEnabled) return;
        Player player = event.getPlayer();
        if (player == null || hasRaceCommandGateAccess(player)) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean shouldBlock = false;
        String reason = "interaction";

        if (rightClick && raceCommandGateBlockItems && matchesRaceGateBlockedItem(player, event.getItem())) {
            shouldBlock = true;
            reason = "blocked item";
        }

        if (rightClick && raceCommandGateBlockSigns && event.getClickedBlock() != null && isSignMaterial(event.getClickedBlock().getType())) {
            shouldBlock = true;
            reason = "sign";
        }

        if (!shouldBlock) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        handleRaceCommandGateBlock(player, reason);
    }

    private void handleRaceCommandGateBlock(Player player, String reason) {
        if (player == null) return;
        debugRaceCommandGate("Bloqueado " + reason + " para " + player.getName());

        long now = System.currentTimeMillis();
        long last = raceGateLastNoticeAt.getOrDefault(player.getUniqueId(), 0L);
        boolean mayNotify = raceCommandGateMessageCooldownMillis <= 0L || now - last >= raceCommandGateMessageCooldownMillis;
        if (mayNotify) {
            raceGateLastNoticeAt.put(player.getUniqueId(), now);
            if (raceCommandGateMessageEnabled) {
                if (raceCommandGateMessageLines == null || raceCommandGateMessageLines.isEmpty()) {
                    player.sendMessage(prefix + color("&cDebes elegir una raza antes de usar comandos. Usa &e/raza&c."));
                } else {
                    for (String line : raceCommandGateMessageLines) player.sendMessage(line);
                }
            }
        }

        String redirect = raceCommandGateRedirectCommand == null ? "raza" : raceCommandGateRedirectCommand.trim();
        if (redirect.startsWith("/")) redirect = redirect.substring(1);
        if (redirect.isBlank()) return;
        String finalRedirect = redirect;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            player.closeInventory();
            Bukkit.dispatchCommand(player, finalRedirect);
        }, Math.max(0, raceCommandGateRedirectDelayTicks));
    }

    private boolean matchesRaceGateBlockedItem(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) return false;
        if (raceCommandGateBlockedItems.isEmpty()) return false;

        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemMeta meta = item.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? normalizePlain(meta.getDisplayName()) : "";

        for (GateBlockedItem blocked : raceCommandGateBlockedItems.values()) {
            if (blocked.material != null && item.getType() != blocked.material) continue;
            if (blocked.slot >= 0 && heldSlot != blocked.slot) continue;
            if (blocked.nameContains != null && !blocked.nameContains.isBlank() && !displayName.contains(blocked.nameContains)) continue;
            return true;
        }
        return false;
    }

    private boolean isSignMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_SIGN");
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

    private static String normalizePlain(String input) {
        if (input == null) return "";
        String stripped = ChatColor.stripColor(color(input));
        if (stripped == null) return "";
        return stripped.trim().toLowerCase(Locale.ROOT);
    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerSkinClearCommandEarly(PlayerCommandPreprocessEvent event) {
        if (!skinMemoryEnabled || !skinMemoryListenPlayerCommands) return;
        Player player = event.getPlayer();
        if (player == null) return;

        SkinCommandAction action = parseSkinCommand(event.getMessage(), player.getName(), true);
        if (action == null || !action.clear) return;

        if (skinMemoryPlayerCommandRequirePermission && !hasAllSkinMemoryPlayerPermissions(player)) {
            debugSkinMemory("/skin clear temprano ignorado por falta de permiso: " + player.getName());
            return;
        }

        String targetName = action.targetName == null || action.targetName.isBlank() ? player.getName() : action.targetName;
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null && targetName.equalsIgnoreCase(player.getName())) target = player;
        if (target == null || !target.isOnline()) {
            debugSkinMemory("/skin clear temprano ignorado porque el objetivo no esta online: " + targetName);
            return;
        }

        // Guardar antes de que SkinsRestorer u otro plugin pueda cancelar/reprocesar el comando.
        rememberNativeSkin(target, "player-command-clear-early");
        debugSkinMemory("Skin nativa guardada temprano desde /skin clear: " + player.getName() + " -> " + target.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!skinMemoryEnabled || !skinMemoryListenPlayerCommands) return;
        Player player = event.getPlayer();
        if (player == null) return;

        SkinCommandAction action = parseSkinCommand(event.getMessage(), player.getName(), true);
        if (action == null) return;

        if (skinMemoryPlayerCommandRequirePermission && !hasAllSkinMemoryPlayerPermissions(player)) {
            debugSkinMemory("Comando /skin de jugador ignorado por falta de permiso: " + player.getName());
            return;
        }
        if (!action.clear && action.skinName != null && !isSafeSkinCommandArgument(action.skinName)) {
            debugSkinMemory("Comando /skin de jugador ignorado por argumento inseguro: " + action.skinName);
            return;
        }

        if (!action.clear && skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(action.skinName)) {
            debugSkinMemory("Comando /skin de jugador ignorado por no estar en catalogo: " + action.skinName);
            return;
        }

        UUID targetUuid = null;
        String targetName = action.targetName == null || action.targetName.isBlank() ? player.getName() : action.targetName;
        Player targetNow = Bukkit.getPlayerExact(targetName);
        if (targetNow != null) targetUuid = targetNow.getUniqueId();
        if (targetUuid == null && targetName.equalsIgnoreCase(player.getName())) targetUuid = player.getUniqueId();
        if (targetUuid == null) {
            debugSkinMemory("Comando /skin de jugador ignorado porque el objetivo no esta online: " + targetName);
            return;
        }

        UUID finalTargetUuid = targetUuid;
        String senderName = player.getName();

        // /skin clear debe guardarse de inmediato. No conviene esperar el delay,
        // porque si el jugador sale rapido antes de que pasen los ticks, se queda
        // guardada la skin anterior y al entrar se reaplica.
        if (action.clear) {
            Player online = Bukkit.getPlayer(finalTargetUuid);
            if (online == null || !online.isOnline()) return;
            rememberNativeSkin(online, "player-command-clear");
            debugSkinMemory("Skin nativa guardada inmediatamente desde comando de jugador: " + senderName + " -> " + online.getName());
            return;
        }

        int delay = Math.max(0, skinMemoryPlayerCommandSaveDelayTicks);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player online = Bukkit.getPlayer(finalTargetUuid);
            if (online == null || !online.isOnline()) return;
            rememberSkin(online, action.skinName, findCatalogKeyForSkin(action.skinName), "player-command");
            debugSkinMemory("Skin guardada desde comando de jugador: " + senderName + " -> " + online.getName() + " = " + action.skinName);
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

    private SkinCommandAction parseSkinCommand(String rawMessage, String defaultTargetName, boolean allowSelfShortcut) {
        if (rawMessage == null || rawMessage.isBlank()) return null;
        String commandLine = rawMessage.trim();
        if (commandLine.startsWith("/")) commandLine = commandLine.substring(1);
        String[] parts = commandLine.split("\\s+");
        if (parts.length < 2) return null;

        String commandName = normalizeCommandName(parts[0]);
        if (commandName.contains(":")) commandName = commandName.substring(commandName.indexOf(':') + 1);
        if (!commandName.equals("skin")) return null;

        String firstArg = parts[1].toLowerCase(Locale.ROOT);
        if (firstArg.equals("clear") || firstArg.equals("reset")) {
            String target = parts.length >= 3 ? parts[2] : defaultTargetName;
            return SkinCommandAction.clear(target);
        }

        if (firstArg.equals("set")) {
            if (parts.length < 3) return null;
            if (parts.length >= 4) return parseTargetedSet(parts[2], parts[3], defaultTargetName);
            if (!allowSelfShortcut) return null;
            return SkinCommandAction.set(parts[2], defaultTargetName);
        }

        if (isNonSkinSubcommand(firstArg)) return null;
        if (!allowSelfShortcut) return null;
        return SkinCommandAction.set(parts[1], defaultTargetName);
    }

    private SkinCommandAction parseTargetedSet(String first, String second, String defaultTargetName) {
        if (first == null || second == null) return null;
        Player secondAsPlayer = Bukkit.getPlayerExact(second);
        if (secondAsPlayer != null) return SkinCommandAction.set(first, secondAsPlayer.getName());

        Player firstAsPlayer = Bukkit.getPlayerExact(first);
        if (firstAsPlayer != null) return SkinCommandAction.set(second, firstAsPlayer.getName());

        // Sintaxis principal usada por MDVAspectos/SkinsRestorer en este servidor: skin set <skin> <player>
        return SkinCommandAction.set(first, second);
    }

    private boolean isNonSkinSubcommand(String value) {
        if (value == null) return true;
        return value.equals("help")
                || value.equals("?")
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
        SkinCommandAction action = parseSkinCommand(event.getCommand(), null, false);
        if (action == null) return;

        if (action.targetName == null || action.targetName.isBlank()) {
            debugSkinMemory("Comando skin de consola ignorado porque no tiene objetivo.");
            return;
        }

        if (action.skinName != null && !action.skinName.isBlank() && !isSafeSkinCommandArgument(action.skinName)) {
            debugSkinMemory("Comando skin de consola ignorado por argumento inseguro: " + action.skinName);
            return;
        }

        if (!action.clear && skinMemoryOnlyConfiguredSkins && !isConfiguredSkin(action.skinName)) {
            debugSkinMemory("Comando skin ignorado por no estar en catalogo: " + action.skinName);
            return;
        }

        Player target = Bukkit.getPlayerExact(action.targetName);
        if (target == null) {
            debugSkinMemory("Comando skin ignorado porque el jugador no esta online: " + action.targetName);
            return;
        }

        if (action.clear) rememberNativeSkin(target, "console-command-clear");
        else rememberSkin(target, action.skinName, findCatalogKeyForSkin(action.skinName), "console-command");
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
            if (button != null && holder.bedrock) button = resolveBedrockButton(button, catalog);
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
            if (sender.hasPermission("mdvaspectos.reload") && "geysersync".startsWith(current)) options.add("geysersync");
            if (sender.hasPermission("mdvaspectos.skinmemory.admin") || sender.hasPermission("mdvaspectos.reload")) {
                if ("aplicarskin".startsWith(current)) options.add("aplicarskin");
                if ("recordarskin".startsWith(current)) options.add("recordarskin");
                if ("nativa".startsWith(current)) options.add("nativa");
            }
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

    private static final class GateBlockedItem {
        private final String key;
        private final Material material;
        private final int slot;
        private final String nameContains;

        private GateBlockedItem(String key, Material material, int slot, String nameContains) {
            this.key = key == null ? "" : key;
            this.material = material;
            this.slot = slot;
            this.nameContains = nameContains == null ? "" : nameContains;
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


    private static final class SkinCommandAction {
        private final boolean clear;
        private final String skinName;
        private final String targetName;

        private SkinCommandAction(boolean clear, String skinName, String targetName) {
            this.clear = clear;
            this.skinName = skinName == null ? "" : skinName;
            this.targetName = targetName == null ? "" : targetName;
        }

        private static SkinCommandAction set(String skinName, String targetName) {
            return new SkinCommandAction(false, skinName, targetName);
        }

        private static SkinCommandAction clear(String targetName) {
            return new SkinCommandAction(true, "", targetName);
        }
    }

    private static final class RememberedSkin {
        private final UUID uuid;
        private final String playerName;
        private final String skinName;
        private final String catalogKey;
        private final String source;
        private final long updatedAt;
        private final boolean nativeSkin;

        private RememberedSkin(UUID uuid, String playerName, String skinName, String catalogKey, String source, long updatedAt, boolean nativeSkin) {
            this.uuid = uuid;
            this.playerName = playerName == null ? "" : playerName;
            this.skinName = skinName == null ? "" : skinName;
            this.catalogKey = catalogKey == null ? "" : catalogKey;
            this.source = source == null ? "unknown" : source;
            this.updatedAt = updatedAt;
            this.nativeSkin = nativeSkin;
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
        private final boolean bedrock;

        private AspectMenuHolder(String catalogKey, boolean bedrock) {
            this.catalogKey = catalogKey;
            this.bedrock = bedrock;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
