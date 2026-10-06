package me.bebo.skipNight;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public final class SkipNight extends JavaPlugin implements Listener, TabExecutor {

    // --- Plugin State ---
    private final Map<UUID, VoteSession> activeVotes = new HashMap<>();
    private final Map<UUID, Long> lastVoteTime = new HashMap<>(); // Per-world cooldown (world UUID -> timestamp)
    private final Map<UUID, Boolean> worldIsNight = new HashMap<>();

    // --- Statistics ---
    private int totalVotesStarted = 0;
    private int successfulVotes = 0;

    // --- Configurable Values ---
    private boolean autoStartVote;
    private String thresholdMode;
    private int requiredPercentage;
    private int requiredAbsoluteCount;
    private boolean allowVoteAgainst;
    private boolean instantSkipAt100;
    private int voteDuration;
    private long voteCooldown;
    private long nightStartTick;
    private long nightEndTick;
    private Map<String, String> messages;
    private List<String> voteStartedMessages;
    private List<String> blacklistedWorlds;

    // Display settings
    private boolean useActionBar;
    private boolean useBossBar;
    private boolean useChat;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;

    // Sound settings
    private Sound soundVoteStarted;
    private Sound soundVotedYes;
    private Sound soundVotedNo;
    private Sound soundVoteSuccess;
    private Sound soundVoteFailed;
    private float soundVolume;
    private float soundPitch;

    // Reward settings
    private boolean rewardsEnabled;
    private boolean rewardsOnlyOnSuccess;
    private int rewardXpLevels;
    private List<RewardItem> rewardItems;
    private double rewardMoney;

    @Override
    public void onEnable() {
        getLogger().info("Enabling SkipNight by Bebo...");

        saveDefaultConfig();
        loadConfigValues();
        loadStats();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("skipnight").setExecutor(this);
        getCommand("skipnightstats").setExecutor(this);

        startDayNightChecker();

        getLogger().info("SkipNight plugin by Bebo has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        activeVotes.values().forEach(VoteSession::cancel);
        activeVotes.clear();
        Bukkit.getScheduler().cancelTasks(this);
        saveStats();
        getLogger().info("SkipNight has been disabled!");
    }

    // --- Configuration & Data Handling ---

    private void loadConfigValues() {
        reloadConfig();
        FileConfiguration config = getConfig();
        config.options().copyDefaults(true);

        autoStartVote = config.getBoolean("vote-settings.auto-start-vote-at-night", true);
        thresholdMode = config.getString("vote-settings.threshold-mode", "percentage").toLowerCase();
        requiredPercentage = Math.max(1, Math.min(100, config.getInt("vote-settings.required-percentage", 50)));
        requiredAbsoluteCount = Math.max(1, config.getInt("vote-settings.required-absolute-count", 3));
        allowVoteAgainst = config.getBoolean("vote-settings.allow-vote-against", true);
        instantSkipAt100 = config.getBoolean("vote-settings.instant-skip-at-100-percent", true);
        voteDuration = Math.max(5, config.getInt("vote-settings.vote-duration-seconds", 30));
        voteCooldown = Math.max(0, config.getInt("vote-settings.vote-start-cooldown-seconds", 300)) * 1000L;
        nightStartTick = config.getLong("vote-settings.night-start-tick", 12541);
        nightEndTick = config.getLong("vote-settings.night-end-tick", 23458);

        // Validate night ticks
        if (nightStartTick >= nightEndTick || nightStartTick < 0 || nightEndTick > 24000) {
            getLogger().warning("Invalid night tick configuration! Using defaults (12541-23458).");
            nightStartTick = 12541;
            nightEndTick = 23458;
        }

        // Load blacklisted worlds (case-insensitive)
        blacklistedWorlds = config.getStringList("vote-settings.blacklisted-worlds");
        for (int i = 0; i < blacklistedWorlds.size(); i++) {
            blacklistedWorlds.set(i, blacklistedWorlds.get(i).toLowerCase());
        }

        // Display settings
        useActionBar = config.getBoolean("display.use-action-bar", true);
        useBossBar = config.getBoolean("display.use-boss-bar", true);
        useChat = config.getBoolean("display.use-chat", true);
        bossBarColor = parseBossBarColor(config.getString("display.boss-bar-color", "YELLOW"));
        bossBarStyle = parseBossBarStyle(config.getString("display.boss-bar-style", "SEGMENTED_10"));

        // Sound settings
        soundVoteStarted = parseSound(config.getString("sounds.vote-started", "BLOCK_NOTE_BLOCK_PLING"));
        soundVotedYes = parseSound(config.getString("sounds.player-voted-yes", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        soundVotedNo = parseSound(config.getString("sounds.player-voted-no", "BLOCK_NOTE_BLOCK_BASS"));
        soundVoteSuccess = parseSound(config.getString("sounds.vote-success", "ENTITY_PLAYER_LEVELUP"));
        soundVoteFailed = parseSound(config.getString("sounds.vote-failed", "ENTITY_VILLAGER_NO"));
        soundVolume = (float) config.getDouble("sounds.volume", 1.0);
        soundPitch = (float) config.getDouble("sounds.pitch", 1.0);

        // Reward settings
        rewardsEnabled = config.getBoolean("rewards.enabled", true);
        rewardsOnlyOnSuccess = config.getBoolean("rewards.only-on-success", false);
        rewardXpLevels = config.getInt("rewards.xp-levels", 1);
        rewardMoney = config.getDouble("rewards.money", 0.0);

        // Load reward items
        rewardItems = new ArrayList<>();
        if (config.isConfigurationSection("rewards.items") || config.isList("rewards.items")) {
            List<?> itemsList = config.getList("rewards.items");
            if (itemsList != null) {
                for (Object obj : itemsList) {
                    if (obj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> itemMap = (Map<String, Object>) obj;
                        try {
                            String materialName = (String) itemMap.get("material");
                            Material mat = Material.getMaterial(materialName);
                            if (mat != null) {
                                int amount = itemMap.containsKey("amount") ? ((Number) itemMap.get("amount")).intValue() : 1;
                                String name = (String) itemMap.get("name");
                                @SuppressWarnings("unchecked")
                                List<String> lore = (List<String>) itemMap.get("lore");
                                rewardItems.add(new RewardItem(mat, amount, name, lore));
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to load reward item: " + e.getMessage());
                        }
                    }
                }
            }
        }

        messages = new HashMap<>();
        if (config.getConfigurationSection("messages") != null) {
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                if (config.isString("messages." + key)) {
                    messages.put(key, config.getString("messages." + key));
                }
            }
            voteStartedMessages = config.getStringList("messages.vote-started");
        }
        saveConfig();
    }

    private Sound parseSound(String soundName) {
        if (soundName == null || soundName.trim().isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound: " + soundName);
            return null;
        }
    }

    private BarColor parseBossBarColor(String colorName) {
        try {
            return BarColor.valueOf(colorName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            getLogger().warning("Invalid display.boss-bar-color '" + colorName + "'. Using YELLOW.");
            return BarColor.YELLOW;
        }
    }

    private BarStyle parseBossBarStyle(String styleName) {
        try {
            return BarStyle.valueOf(styleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            getLogger().warning("Invalid display.boss-bar-style '" + styleName + "'. Using SEGMENTED_10.");
            return BarStyle.SEGMENTED_10;
        }
    }

    private void loadStats() {
        FileConfiguration config = getConfig();
        this.totalVotesStarted = config.getInt("statistics.total-votes-started", 0);
        this.successfulVotes = config.getInt("statistics.successful-votes", 0);
    }

    private void saveStats() {
        FileConfiguration config = getConfig();
        config.set("statistics.total-votes-started", this.totalVotesStarted);
        config.set("statistics.successful-votes", this.successfulVotes);
        saveConfig();
    }

    // --- Command Handling ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skipnight")) {
            if (args.length > 0) {
                String subCmd = args[0].toLowerCase();
                switch (subCmd) {
                    case "reload":
                        handleReload(sender);
                        return true;
                    case "stats":
                        handleStats(sender);
                        return true;
                    case "gui":
                        handleGui(sender);
                        return true;
                    case "help":
                        handleHelp(sender);
                        return true;
                    case "yes":
                        handleVoteYes(sender);
                        return true;
                    case "no":
                        handleVoteNo(sender);
                        return true;
                    case "cancel":
                        handleCancelVote(sender);
                        return true;
                }
            }
            // Default: start vote or vote yes
            handleVoteCommand(sender);
            return true;
        } else if (command.getName().equalsIgnoreCase("skipnightstats")) {
            handleStats(sender);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("skipnight") && args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            if (sender.hasPermission("skipnight.vote.yes") && "yes".startsWith(input)) {
                completions.add("yes");
            }
            if (sender.hasPermission("skipnight.vote.no") && "no".startsWith(input)) {
                completions.add("no");
            }
            if (sender.hasPermission("skipnight.reload") && "reload".startsWith(input)) {
                completions.add("reload");
            }
            if (sender.hasPermission("skipnight.stats") && "stats".startsWith(input)) {
                completions.add("stats");
            }
            if (sender.hasPermission("skipnight.gui") && "gui".startsWith(input)) {
                completions.add("gui");
            }
            if (sender.hasPermission("skipnight.cancel") && "cancel".startsWith(input)) {
                completions.add("cancel");
            }
            if ("help".startsWith(input)) {
                completions.add("help");
            }

            return completions;
        }
        return null;
    }

    private void handleHelp(CommandSender sender) {
        sender.sendMessage(colorize("&6&l--- SkipNight Help ---"));
        sender.sendMessage(colorize("&e/skipnight &7- Start a vote or vote yes"));
        sender.sendMessage(colorize("&e/skipnight yes &7- Vote YES to skip night"));
        if (allowVoteAgainst) {
            sender.sendMessage(colorize("&e/skipnight no &7- Vote NO against skipping"));
        }
        sender.sendMessage(colorize("&e/skipnight gui &7- Open the vote status GUI"));
        if (sender.hasPermission("skipnight.stats")) {
            sender.sendMessage(colorize("&e/skipnight stats &7- View voting statistics"));
        }
        if (sender.hasPermission("skipnight.cancel")) {
            sender.sendMessage(colorize("&e/skipnight cancel &7- Cancel ongoing vote"));
        }
        if (sender.hasPermission("skipnight.reload")) {
            sender.sendMessage(colorize("&e/skipnight reload &7- Reload plugin configuration"));
        }
    }

    private void handleVoteYes(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendMessageSafely(sender, "error-not-a-player");
            return;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("skipnight.vote.yes")) {
            sendMessageSafely(player, "no-permission");
            return;
        }

        World world = player.getWorld();
        if (isWorldBlacklisted(world)) {
            sendMessageSafely(player, "error-world-blacklisted");
            return;
        }
        if (world.getEnvironment() != World.Environment.NORMAL) {
            sendMessageSafely(player, "error-wrong-world");
            return;
        }

        VoteSession session = activeVotes.get(world.getUID());
        if (session != null) {
            session.addVote(player, true, false);
        } else {
            startVoteAttempt(player);
        }
    }

    private void handleVoteNo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendMessageSafely(sender, "error-not-a-player");
            return;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("skipnight.vote.no")) {
            sendMessageSafely(player, "no-permission");
            return;
        }

        if (!allowVoteAgainst) {
            sendMessageSafely(player, "error-vote-against-disabled");
            return;
        }

        World world = player.getWorld();
        if (isWorldBlacklisted(world)) {
            sendMessageSafely(player, "error-world-blacklisted");
            return;
        }
        if (world.getEnvironment() != World.Environment.NORMAL) {
            sendMessageSafely(player, "error-wrong-world");
            return;
        }

        VoteSession session = activeVotes.get(world.getUID());
        if (session != null) {
            session.addVote(player, false, false);
        } else {
            sendMessageSafely(player, "error-no-vote-in-progress");
        }
    }

    private void handleCancelVote(CommandSender sender) {
        if (!sender.hasPermission("skipnight.cancel")) {
            sendMessageSafely(sender, "no-permission");
            return;
        }

        if (!(sender instanceof Player)) {
            sendMessageSafely(sender, "error-not-a-player");
            return;
        }

        Player player = (Player) sender;
        World world = player.getWorld();
        VoteSession session = activeVotes.get(world.getUID());

        if (session == null) {
            sendMessageSafely(player, "error-no-vote-in-progress");
            return;
        }

        session.cancelByAdmin();
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendMessageSafely(sender, "error-not-a-player");
            return;
        }
        Player player = (Player) sender;
        VoteSession session = activeVotes.get(player.getWorld().getUID());
        if (session == null) {
            sendMessageSafely(player, "error-no-vote-in-progress");
            return;
        }
        session.openVoteGui(player);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("skipnight.reload")) {
            sendMessageSafely(sender, "no-permission");
            return;
        }
        loadConfigValues();
        for (VoteSession session : activeVotes.values()) {
            session.reloadBossBarSettings();
        }
        sendMessageSafely(sender, "reload-success");
    }

    private void handleStats(CommandSender sender) {
        if (!sender.hasPermission("skipnight.stats")) {
            sendMessageSafely(sender, "no-permission");
            return;
        }

        double successRate = (totalVotesStarted > 0) ? (successfulVotes * 100.0 / totalVotesStarted) : 0.0;
        sendMessageSafely(sender, "stats-header");
        sendMessageSafely(sender, "stats-total-votes", "{total_started}", String.valueOf(totalVotesStarted));
        sendMessageSafely(sender, "stats-successful-votes", "{successful_votes}", String.valueOf(successfulVotes));
        sendMessageSafely(sender, "stats-success-rate", "{success_rate}", String.format("%.1f", successRate));
    }

    private void handleVoteCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendMessageSafely(sender, "error-not-a-player");
            return;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("skipnight.vote")) {
            sendMessageSafely(player, "no-permission");
            return;
        }

        World world = player.getWorld();
        if (isWorldBlacklisted(world)) {
            sendMessageSafely(player, "error-world-blacklisted");
            return;
        }

        if (world.getEnvironment() != World.Environment.NORMAL) {
            sendMessageSafely(player, "error-wrong-world");
            return;
        }

        VoteSession session = activeVotes.get(world.getUID());

        if (session != null) {
            // Default to voting yes
            session.addVote(player, true, false);
        } else {
            startVoteAttempt(player);
        }
    }

    // --- Vote Logic ---

    private void startVoteAttempt(Player player) {
        World world = player.getWorld();

        if (activeVotes.containsKey(world.getUID())) {
            sendMessageSafely(player, "error-vote-in-progress");
            return;
        }

        // Per-world cooldown instead of per-player
        long currentTime = System.currentTimeMillis();
        UUID worldId = world.getUID();
        if (lastVoteTime.containsKey(worldId) && (currentTime - lastVoteTime.get(worldId) < voteCooldown)) {
            sendMessageSafely(player, "error-cooldown");
            return;
        }

        if (world.getTime() < nightStartTick || world.getTime() > nightEndTick) {
            sendMessageSafely(player, "error-not-night");
            return;
        }

        lastVoteTime.put(worldId, currentTime);
        startVote(world, player);
    }

    public void startVote(World world, @Nullable Player initiator) {
        if (isWorldBlacklisted(world) || world.getEnvironment() != World.Environment.NORMAL || activeVotes.containsKey(world.getUID())) {
            return;
        }

        VoteSession session = new VoteSession(world, initiator);
        activeVotes.put(world.getUID(), session);

        totalVotesStarted++;
        saveStats();

        session.start();
    }

    private boolean isWorldBlacklisted(World world) {
        return blacklistedWorlds.contains(world.getName().toLowerCase());
    }


    // --- Schedulers ---

    private void startDayNightChecker() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!autoStartVote) return;

            for (World world : Bukkit.getWorlds()) {
                if (isWorldBlacklisted(world) || world.getEnvironment() != World.Environment.NORMAL || world.getPlayers().isEmpty() || activeVotes.containsKey(world.getUID())) {
                    continue;
                }

                long time = world.getTime();
                boolean isCurrentlyNight = (time >= nightStartTick && time <= nightEndTick);
                boolean wasNight = worldIsNight.getOrDefault(world.getUID(), isCurrentlyNight);

                if (isCurrentlyNight && !wasNight) {
                    getLogger().info("Night detected in world '" + world.getName() + "'. Starting a vote.");
                    startVote(world, null);
                }

                worldIsNight.put(world.getUID(), isCurrentlyNight);
            }
        }, 100L, 100L);
    }


    // --- Event Handlers ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = formatMessage("gui-title");
        // Check for null title to avoid errors
        if (title != null && event.getView().getTitle().equals(title)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;

        Player player = event.getPlayer();
        VoteSession session = activeVotes.get(player.getWorld().getUID());
        if (session != null) {
            session.addVote(player, true, true); // true for yes vote, true for from bed
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        VoteSession session = activeVotes.get(player.getWorld().getUID());
        if (session != null) {
            session.handlePlayerStateChange();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        VoteSession session = activeVotes.get(player.getWorld().getUID());
        if (session != null) {
            session.notifyPlayerOnJoin(player);
            session.handlePlayerStateChange();
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        VoteSession fromSession = activeVotes.get(event.getFrom().getUID());
        if (fromSession != null) {
            fromSession.handlePlayerStateChange();
        }

        VoteSession toSession = activeVotes.get(player.getWorld().getUID());
        if (toSession != null) {
            toSession.notifyPlayerOnJoin(player);
            toSession.handlePlayerStateChange();
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        // Clean up to prevent memory leaks
        worldIsNight.remove(worldId);
        lastVoteTime.remove(worldId);
        VoteSession session = activeVotes.get(worldId);
        if (session != null) {
            session.end();
        }
    }


    // --- Utility Methods ---

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // Helper to send message only if it exists in config
    public void sendMessageSafely(CommandSender sender, String key, String... placeholders) {
        String msg = formatMessage(key, placeholders);
        if (msg != null && !msg.isEmpty()) {
            sender.sendMessage(msg);
        }
    }

    @Nullable
    public String formatMessage(String key, String... placeholders) {
        // Fix 1: Check if key exists; if not, return null (allows disabling messages)
        if (!messages.containsKey(key)) {
            return null;
        }

        String message = messages.get(key);

        // Fix 2: If message is empty string in config, return null
        if (message == null || message.trim().isEmpty()) {
            return null;
        }

        message = message.replace("{prefix}", messages.getOrDefault("prefix", ""));

        if (placeholders.length > 0) {
            for (int i = 0; i < placeholders.length; i += 2) {
                if (i + 1 < placeholders.length) {
                    message = message.replace(placeholders[i], placeholders[i + 1]);
                }
            }
        }
        return colorize(message);
    }

    // --- Helper method for playing sounds ---

    private void playSound(Player player, Sound sound) {
        if (sound != null) {
            player.playSound(player.getLocation(), sound, soundVolume, soundPitch);
        }
    }

    private void playSoundToWorld(World world, Sound sound) {
        if (sound != null) {
            for (Player p : world.getPlayers()) {
                playSound(p, sound);
            }
        }
    }

    // --- Helper method for giving rewards ---

    private void giveReward(Player player) {
        if (!rewardsEnabled) return;

        // XP
        if (rewardXpLevels > 0) {
            player.giveExpLevels(rewardXpLevels);
        }

        // Items
        for (RewardItem reward : rewardItems) {
            ItemStack item = new ItemStack(reward.material, reward.amount);
            if (reward.displayName != null || reward.lore != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    if (reward.displayName != null) {
                        meta.setDisplayName(colorize(reward.displayName));
                    }
                    if (reward.lore != null) {
                        meta.setLore(reward.lore.stream().map(this::colorize).collect(Collectors.toList()));
                    }
                    item.setItemMeta(meta);
                }
            }
            player.getInventory().addItem(item);
        }

        // Money (Vault integration - optional)
        if (rewardMoney > 0) {
            // Note: This requires Vault plugin - gracefully skip if not available
            try {
                // We'll make this optional - admin must set up Vault separately
                // For now, just log that economy reward is configured but not implemented
                // A full Vault integration would require adding the dependency
            } catch (Exception ignored) {
            }
        }

        sendMessageSafely(player, "reward-received");
    }

    // --- Inner Classes ---

    private static class RewardItem {
        final Material material;
        final int amount;
        final String displayName;
        final List<String> lore;

        RewardItem(Material material, int amount, String displayName, List<String> lore) {
            this.material = material;
            this.amount = amount;
            this.displayName = displayName;
            this.lore = lore;
        }
    }

    private class VoteSession {
        private final World world;
        private final Set<UUID> yesVotes = new HashSet<>();
        private final Set<UUID> noVotes = new HashSet<>();
        private int timeLeft;
        private BukkitTask timerTask;
        private BossBar bossBar;
        private Inventory voteGui;

        VoteSession(World world, @Nullable Player initiator) {
            this.world = world;
            this.timeLeft = voteDuration;
            if (initiator != null) {
                yesVotes.add(initiator.getUniqueId());
            }

            configureBossBar();
        }

        private void configureBossBar() {
            if (!useBossBar) {
                if (bossBar != null) {
                    bossBar.removeAll();
                    bossBar = null;
                }
                return;
            }

            if (bossBar == null) {
                bossBar = Bukkit.createBossBar(
                        colorize("&eVote to Skip Night"),
                        bossBarColor,
                        bossBarStyle
                );
                for (Player player : world.getPlayers()) {
                    bossBar.addPlayer(player);
                }
            } else {
                bossBar.setColor(bossBarColor);
                bossBar.setStyle(bossBarStyle);
            }
        }

        void reloadBossBarSettings() {
            configureBossBar();
        }

        void start() {
            String prefix = messages.getOrDefault("prefix", "");

            if (useChat && voteStartedMessages != null) {
                for (String line : voteStartedMessages) {
                    if (line != null && !line.isEmpty()) {
                        broadcast(colorize(line.replace("{prefix}", prefix).replace("{percentage}", String.valueOf(requiredPercentage))));
                    }
                }
            }

            // Clickable message logic - two buttons for YES and NO
            if (useChat) {
                TextComponent yesButton = new TextComponent(formatMessage("vote-started-clickable-yes"));
                yesButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/skipnight yes"));
                yesButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text(colorize("&aClick to vote YES"))));

                TextComponent separator = new TextComponent("  ");

                TextComponent message;
                if (allowVoteAgainst) {
                    TextComponent noButton = new TextComponent(formatMessage("vote-started-clickable-no"));
                    noButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/skipnight no"));
                    noButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text(colorize("&cClick to vote NO"))));
                    message = new TextComponent(yesButton, separator, noButton);
                } else {
                    message = yesButton;
                }

                for (Player p : world.getPlayers()) {
                    p.spigot().sendMessage(message);
                }
            }

            // Play sound
            playSoundToWorld(world, soundVoteStarted);

            startTimer();
            checkVotes(false);
        }

        void startTimer() {
            this.timerTask = Bukkit.getScheduler().runTaskTimer(SkipNight.this, () -> {
                if (timeLeft <= 0) {
                    if (useChat) {
                        broadcast(formatMessage("vote-ended-failure"));
                    }
                    playSoundToWorld(world, soundVoteFailed);
                    end();
                    return;
                }

                if (useChat && (timeLeft % 10 == 0 || timeLeft <= 5)) {
                    broadcast(formatMessage("time-remaining", "{time}", String.valueOf(timeLeft)));
                }

                // Update boss bar
                if (useBossBar && bossBar != null) {
                    double progress = Math.max(0.0, Math.min(1.0, (double) timeLeft / voteDuration));
                    bossBar.setProgress(progress);

                    int yesCount = (int) yesVotes.stream().filter(uuid -> {
                        Player p = Bukkit.getPlayer(uuid);
                        return p != null && p.getWorld().equals(world);
                    }).count();
                    int noCount = (int) noVotes.stream().filter(uuid -> {
                        Player p = Bukkit.getPlayer(uuid);
                        return p != null && p.getWorld().equals(world);
                    }).count();

                    String barTitle = colorize(String.format("&eYES: &a%d &7| NO: &c%d &7| Time: &6%ds", yesCount, noCount, timeLeft));
                    bossBar.setTitle(barTitle);
                }

                updateGui();

                timeLeft--;
            }, 20L, 20L);
        }

        void cancelByAdmin() {
            if (useChat) {
                broadcast(formatMessage("vote-cancelled"));
            }
            end();
        }

        void notifyPlayerOnJoin(Player player) {
            if (useChat) {
                sendMessageSafely(player, "vote-in-progress-on-join");

                TextComponent yesButton = new TextComponent(formatMessage("vote-started-clickable-yes"));
                yesButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/skipnight yes"));
                yesButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text(colorize("&aClick to vote YES"))));

                if (allowVoteAgainst) {
                    TextComponent separator = new TextComponent("  ");
                    TextComponent noButton = new TextComponent(formatMessage("vote-started-clickable-no"));
                    noButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/skipnight no"));
                    noButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text(colorize("&cClick to vote NO"))));
                    player.spigot().sendMessage(new TextComponent(yesButton, separator, noButton));
                } else {
                    player.spigot().sendMessage(yesButton);
                }
            }

            // Add to boss bar if it exists
            if (useBossBar && bossBar != null) {
                bossBar.addPlayer(player);
            }
        }

        void addVote(Player player, boolean voteYes, boolean fromBed) {
            UUID playerId = player.getUniqueId();

            // Check if already voted
            if (yesVotes.contains(playerId) || noVotes.contains(playerId)) {
                sendMessageSafely(player, "already-voted");
                return;
            }

            // Add vote
            if (voteYes) {
                yesVotes.add(playerId);
                if (useChat) {
                    sendMessageSafely(player, fromBed ? "voted-successfully-bed" : "voted-yes");
                }
                playSound(player, soundVotedYes);
            } else {
                noVotes.add(playerId);
                if (useChat) {
                    sendMessageSafely(player, "voted-no");
                }
                playSound(player, soundVotedNo);
            }

            checkVotes(true);
        }

        void handlePlayerStateChange() {
            Bukkit.getScheduler().runTaskLater(SkipNight.this, () -> checkVotes(true), 1L);
        }

        void checkVotes(boolean announceProgress) {
            List<Player> playersInWorld = world.getPlayers();
            int onlineInWorld = playersInWorld.size();

            if (onlineInWorld == 0) {
                end();
                return;
            }

            long yesCount = yesVotes.stream().filter(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                return p != null && p.getWorld().equals(world);
            }).count();

            long noCount = noVotes.stream().filter(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                return p != null && p.getWorld().equals(world);
            }).count();

            boolean passed = false;

            if (thresholdMode.equals("absolute")) {
                // Absolute mode: need X specific number of yes votes
                passed = yesCount >= requiredAbsoluteCount;
            } else {
                // Percentage mode: need X% of online players to vote yes
                double currentPercentage = (double) yesCount * 100 / onlineInWorld;
                passed = currentPercentage >= requiredPercentage;
            }

            // Instant skip at 100%
            if (instantSkipAt100 && yesCount == onlineInWorld) {
                passed = true;
            }

            if (passed) {
                if (useChat) {
                    broadcast(formatMessage("vote-ended-success"));
                }
                playSoundToWorld(world, soundVoteSuccess);
                successfulVotes++;
                saveStats();

                // Give rewards to voters
                if (rewardsEnabled && (rewardsOnlyOnSuccess || true)) {
                    Set<UUID> allVoters = new HashSet<>(yesVotes);
                    allVoters.addAll(noVotes);
                    for (UUID voterId : allVoters) {
                        Player voter = Bukkit.getPlayer(voterId);
                        if (voter != null && voter.getWorld().equals(world)) {
                            giveReward(voter);
                        }
                    }
                }

                Bukkit.getScheduler().runTask(SkipNight.this, () -> {
                    world.setTime(0);
                    world.setStorm(false);
                    world.setThundering(false);
                });
                end();
            } else if (announceProgress) {
                String progressMsg;
                if (thresholdMode.equals("absolute")) {
                    progressMsg = formatMessage("vote-progress-absolute",
                            "{yes_votes}", String.valueOf(yesCount),
                            "{no_votes}", String.valueOf(noCount),
                            "{required}", String.valueOf(requiredAbsoluteCount)
                    );
                } else {
                    progressMsg = formatMessage("vote-progress",
                            "{yes_votes}", String.valueOf(yesCount),
                            "{no_votes}", String.valueOf(noCount),
                            "{percentage}", String.valueOf(requiredPercentage)
                    );
                }

                if (useActionBar) {
                    // Send as action bar
                    for (Player p : playersInWorld) {
                        if (progressMsg != null) {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(progressMsg));
                        }
                    }
                } else if (useChat) {
                    broadcast(progressMsg);
                }
            }
            updateGui();
        }

        void openVoteGui(Player player) {
            if(voteGui == null) {
                createGui();
            }
            updateGui();
            if (voteGui != null) {
                player.openInventory(voteGui);
            }
        }

        void createGui() {
            String title = formatMessage("gui-title");
            if (title == null) title = "Skip Night Vote"; // Fallback if title is deleted
            voteGui = Bukkit.createInventory(null, 54, title);
        }

        void updateGui() {
            if (voteGui == null) createGui();
            voteGui.clear();

            List<Player> playersInWorld = world.getPlayers();
            long yesCount = yesVotes.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).count();
            long noCount = noVotes.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).count();

            ItemStack infoItem = new ItemStack(Material.CLOCK);
            ItemMeta infoMeta = infoItem.getItemMeta();
            infoMeta.setDisplayName(colorize("&eVote Status"));
            List<String> lore = new ArrayList<>();
            lore.add(colorize("&7Time Remaining: &c" + timeLeft + "s"));
            lore.add(colorize("&7YES Votes: &a" + yesCount));
            lore.add(colorize("&7NO Votes: &c" + noCount));
            lore.add(colorize("&7Total Players: &e" + playersInWorld.size()));

            if (thresholdMode.equals("absolute")) {
                lore.add(colorize("&7Required: &b" + requiredAbsoluteCount + " YES votes"));
            } else {
                lore.add(colorize("&7Required: &b" + requiredPercentage + "%"));
            }

            infoMeta.setLore(lore);
            infoItem.setItemMeta(infoMeta);
            voteGui.setItem(4, infoItem);

            for(Player p : playersInWorld) {
                ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD, 1);
                SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
                skullMeta.setOwningPlayer(p);

                UUID playerId = p.getUniqueId();
                boolean votedYes = yesVotes.contains(playerId);
                boolean votedNo = noVotes.contains(playerId);

                String displayName;
                String voteLore;

                if (votedYes) {
                    displayName = colorize("&a" + p.getName());
                    voteLore = formatMessage("gui-voter-yes");
                } else if (votedNo) {
                    displayName = colorize("&c" + p.getName());
                    voteLore = formatMessage("gui-voter-no");
                } else {
                    displayName = colorize("&7" + p.getName());
                    voteLore = formatMessage("gui-voter-abstain");
                }

                skullMeta.setDisplayName(displayName);
                List<String> skullLore = new ArrayList<>();
                if (voteLore != null) {
                    skullLore.add(voteLore);
                }
                skullMeta.setLore(skullLore);
                playerHead.setItemMeta(skullMeta);
                voteGui.addItem(playerHead);
            }
        }

        void end() {
            cancel();
            activeVotes.remove(this.world.getUID());
            if (voteGui != null) {
                new ArrayList<>(voteGui.getViewers()).forEach(human -> human.closeInventory());
            }

            // Clean up boss bar
            if (bossBar != null) {
                bossBar.removeAll();
                bossBar = null;
            }
        }

        void cancel() {
            if (timerTask != null && !timerTask.isCancelled()) {
                timerTask.cancel();
            }
        }

        void broadcast(@Nullable String message) {
            if (message == null || message.trim().isEmpty()) return;
            for (Player player : this.world.getPlayers()) {
                player.sendMessage(message);
            }
        }
    }
}