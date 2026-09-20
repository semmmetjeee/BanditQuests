package me.semmmetje.banditquests;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** A fully configurable, permanent autumn quest pass. */
public final class BanditQuests extends JavaPlugin implements Listener, CommandExecutor {
  private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
  private final Map<String, Quest> quests = new LinkedHashMap<>();
  private final Map<UUID, PlayerData> data = new HashMap<>();
  private File playerFolder;
  private YamlConfiguration gui;

  record Level(int goal, List<String> rewards) {}
  record Quest(String id, String type, String name, List<String> description, Material icon,
               Set<String> whitelist, Set<String> blacklist, int minimumChatLength, List<Level> levels) {}
  static final class PlayerData {
    final Map<String, Integer> progress = new HashMap<>();
    final Map<String, Integer> level = new HashMap<>();
    final Set<String> readyToClaim = new HashSet<>();
  }
  record PassHolder() implements InventoryHolder { @Override public Inventory getInventory() { return null; } }

  @Override public void onEnable() {
    saveDefaultConfig(); saveResource("quests.yml", false); saveResource("gui.yml", false);
    playerFolder = new File(getDataFolder(), "players"); playerFolder.mkdirs(); reloadAll();
    Objects.requireNonNull(getCommand("herfstpas")).setExecutor(this);
    Objects.requireNonNull(getCommand("banditquests")).setExecutor(this);
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(p -> track(p, "PLAY_MINUTES", null, 1)), 1200L, 1200L);
  }

  private void reloadAll() {
    reloadConfig(); quests.clear();
    YamlConfiguration file = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "quests.yml"));
    ConfigurationSection root = file.getConfigurationSection("quests");
    if (root != null) for (String id : root.getKeys(false)) {
      ConfigurationSection s = root.getConfigurationSection(id); if (s == null) continue;
      List<Level> levels = new ArrayList<>();
      ConfigurationSection levelRoot = s.getConfigurationSection("levels");
      if (levelRoot != null) for (String key : levelRoot.getKeys(false)) {
        ConfigurationSection level = levelRoot.getConfigurationSection(key); if (level == null) continue;
        levels.add(new Level(Math.max(1, level.getInt("goal", 1)), level.getStringList("rewards.commands")));
      }
      if (levels.isEmpty()) continue;
      Material icon = Material.matchMaterial(s.getString("display.icon", "PAPER"));
      quests.put(id, new Quest(id, s.getString("type", "BLOCK_BREAK").toUpperCase(Locale.ROOT),
          s.getString("display.name", id), s.getStringList("display.description"), icon == null ? Material.PAPER : icon,
          values(s, "filters.whitelist"), values(s, "filters.blacklist"), Math.max(0, s.getInt("options.minimum-chat-length", 0)), levels));
    }
    gui = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "gui.yml"));
  }
  private Set<String> values(ConfigurationSection s, String path) { return s.getStringList(path).stream().map(v -> v.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
  private PlayerData get(Player p) { return data.computeIfAbsent(p.getUniqueId(), this::load); }
  private PlayerData load(UUID id) {
    YamlConfiguration file = YamlConfiguration.loadConfiguration(new File(playerFolder, id + ".yml")); PlayerData value = new PlayerData();
    ConfigurationSection progress = file.getConfigurationSection("progress"); if (progress != null) for (String key : progress.getKeys(false)) value.progress.put(key, progress.getInt(key));
    ConfigurationSection level = file.getConfigurationSection("level"); if (level != null) for (String key : level.getKeys(false)) value.level.put(key, level.getInt(key));
    value.readyToClaim.addAll(file.getStringList("ready-to-claim"));
    return value;
  }
  private void save(Player p, PlayerData value) {
    YamlConfiguration file = new YamlConfiguration(); value.progress.forEach((key, amount) -> file.set("progress." + key, amount)); value.level.forEach((key, amount) -> file.set("level." + key, amount)); file.set("ready-to-claim", new ArrayList<>(value.readyToClaim));
    try { file.save(new File(playerFolder, p.getUniqueId() + ".yml")); } catch (IOException e) { getLogger().warning("Could not save quest data: " + e.getMessage()); }
  }

  private void track(Player p, String event, String target, int amount) {
    PlayerData value = get(p); boolean changed = false;
    for (Quest q : quests.values()) if (q.type.equalsIgnoreCase(event) && matches(q, target)) { advance(p, value, q, amount); changed = true; }
    if (changed) save(p, value);
  }
  private boolean matches(Quest q, String target) {
    if (target == null) return true; String key = target.toUpperCase(Locale.ROOT);
    return (q.whitelist.isEmpty() || q.whitelist.contains(key)) && !q.blacklist.contains(key);
  }
  private void advance(Player p, PlayerData value, Quest q, int amount) {
    int levelIndex = value.level.getOrDefault(q.id, 0); if (levelIndex >= q.levels.size() || value.readyToClaim.contains(q.id)) return;
    int progress = value.progress.getOrDefault(q.id, 0) + amount;
    Level level = q.levels.get(levelIndex);
    if (progress >= level.goal) { progress = level.goal; value.readyToClaim.add(q.id); message(p, "reward-ready", Map.of("quest", plain(q.name), "level", String.valueOf(levelIndex + 1))); }
    value.progress.put(q.id, progress);
  }

  @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { track(e.getPlayer(), "BLOCK_BREAK", e.getBlock().getType().name(), 1); }
  @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { track(e.getPlayer(), "BLOCK_PLACE", e.getBlockPlaced().getType().name(), 1); }
  /** A pumpkin is carved by right-clicking it with shears; it is not broken. */
  @EventHandler(ignoreCancelled = true) public void onPumpkinCarve(PlayerInteractEvent e) {
    if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.PUMPKIN) return;
    ItemStack hand = e.getItem();
    if (hand != null && hand.getType() == Material.SHEARS) track(e.getPlayer(), "PUMPKIN_CARVE", null, 1);
  }
  @EventHandler(ignoreCancelled = true) public void onFish(PlayerFishEvent e) { if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) track(e.getPlayer(), "FISH", null, 1); }
  @EventHandler(ignoreCancelled = true) public void onCraft(CraftItemEvent e) { if (e.getWhoClicked() instanceof Player p && e.getRecipe().getResult().getType() == Material.PUMPKIN_PIE) track(p, "CRAFT", "PUMPKIN_PIE", 1); }
  @EventHandler(ignoreCancelled = true) public void onPlayerKill(PlayerDeathEvent e) { Player killer = e.getEntity().getKiller(); if (killer != null) track(killer, "KILL_PLAYER", null, 1); }
  @EventHandler(ignoreCancelled = true) public void onMobKill(EntityDeathEvent e) { if (!(e.getEntity() instanceof Player) && e.getEntity().getKiller() != null) track(e.getEntity().getKiller(), "KILL_MOB", e.getEntityType().name(), 1); }
  @EventHandler(ignoreCancelled = true) public void onChat(AsyncPlayerChatEvent e) { int length = e.getMessage().trim().length(); Bukkit.getScheduler().runTask(this, () -> trackChat(e.getPlayer(), length)); }
  private void trackChat(Player p, int length) {
    PlayerData value = get(p); boolean changed = false;
    for (Quest q : quests.values()) if (q.type.equals("CHAT") && length >= q.minimumChatLength) { advance(p, value, q, 1); changed = true; }
    if (changed) save(p, value);
  }
  @EventHandler public void onJoin(PlayerJoinEvent e) { get(e.getPlayer()); track(e.getPlayer(), "JOIN", null, 1); }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (command.getName().equalsIgnoreCase("banditquests")) {
      if (!sender.hasPermission("banditquests.admin")) { if (sender instanceof Player p) message(p, "no-permission", Map.of()); return true; }
      if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { reloadAll(); sender.sendMessage(color(getConfig().getString("messages.reload"))); return true; }
      sender.sendMessage(color("&6Use: &f/banditquests reload")); return true;
    }
    if (sender instanceof Player p) open(p); else sender.sendMessage("This command is for players only."); return true;
  }

  private void open(Player p) {
    PlayerData value = get(p); int size = gui.getInt("board.size", 54);
    Inventory inv = Bukkit.createInventory(new PassHolder(), size, color(gui.getString("board.title", "Herfstpas")));
    Material border = material(gui.getString("board.border.material", "ORANGE_STAINED_GLASS_PANE"), Material.ORANGE_STAINED_GLASS_PANE);
    for (int slot = 0; slot < size; slot++) if (slot < 9 || slot >= size - 9 || slot % 9 == 0 || slot % 9 == 8) inv.setItem(slot, item(border, gui.getString("board.border.name", " "), List.of()));
    inv.setItem(gui.getInt("board.info.slot", 4), item(material(gui.getString("board.info.material", "JACK_O_LANTERN"), Material.JACK_O_LANTERN), gui.getString("board.info.name"), gui.getStringList("board.info.lore")));
    List<Integer> slots = gui.getIntegerList("board.quest-slots"); int i = 0;
    for (Quest q : quests.values()) { if (i >= slots.size()) break; int levelIndex = value.level.getOrDefault(q.id, 0); boolean done = levelIndex >= q.levels.size(); int progress = value.progress.getOrDefault(q.id, 0); Level level = done ? q.levels.get(q.levels.size()-1) : q.levels.get(levelIndex);
      boolean ready = value.readyToClaim.contains(q.id);
      String bottomLine = gui.getString(done ? "quest.bottom-lines.completed" : ready ? "quest.bottom-lines.ready" : "quest.bottom-lines.active", "");
      Map<String,String> vars = Map.of("name",q.name,"progress",String.valueOf(Math.min(progress, level.goal)),"goal",String.valueOf(level.goal),"unit",progressUnit(q),"level",String.valueOf(Math.min(levelIndex+1,q.levels.size())),"max-level",String.valueOf(q.levels.size()),"bottom-line",bottomLine);
      List<String> lore = new ArrayList<>();
      for (String line : gui.getStringList("quest.header-lore")) lore.add(replace(line, vars));
      for (String line : q.description) lore.add(replace(line, vars));
      for (String line : gui.getStringList("quest.footer-lore")) lore.add(replace(line, vars));
      inv.setItem(slots.get(i++), item(done ? material(gui.getString("quest.materials.completed","LIME_DYE"),Material.LIME_DYE) : q.icon, replace(gui.getString("quest.name","%name%"),vars), lore));
    } p.openInventory(inv);
  }
  @EventHandler public void click(InventoryClickEvent e) {
    if (!(e.getInventory().getHolder() instanceof PassHolder) || !(e.getWhoClicked() instanceof Player p)) return;
    e.setCancelled(true); int index = gui.getIntegerList("board.quest-slots").indexOf(e.getRawSlot()); if (index < 0) return;
    Quest q = quests.values().stream().skip(index).findFirst().orElse(null); if (q == null) return; PlayerData value = get(p);
    if (!value.readyToClaim.contains(q.id)) return;
    int levelIndex = value.level.getOrDefault(q.id, 0); if (levelIndex >= q.levels.size()) return; Level level = q.levels.get(levelIndex);
    for (String command : level.rewards) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replace(command, Map.of("player", p.getName(), "quest", q.id, "level", String.valueOf(levelIndex + 1), "goal", String.valueOf(level.goal))));
    value.readyToClaim.remove(q.id); value.progress.put(q.id, 0); value.level.put(q.id, levelIndex + 1); save(p, value);
    message(p, levelIndex + 1 >= q.levels.size() ? "quest-complete" : "reward-claimed", Map.of("quest", plain(q.name), "level", String.valueOf(levelIndex + 1)));
    Bukkit.getScheduler().runTask(this, () -> open(p));
  }
  @EventHandler public void drag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof PassHolder && e.getRawSlots().stream().anyMatch(slot -> slot < e.getInventory().getSize())) e.setCancelled(true); }
  private Material material(String value, Material fallback) { Material material = Material.matchMaterial(value == null ? "" : value); return material == null ? fallback : material; }
  private String progressUnit(Quest quest) { return quest.type.equals("PLAY_MINUTES") ? " Minuten" : ""; }
  private ItemStack item(Material material, String name, List<String> lore) { ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(color(name)); meta.setLore(lore.stream().map(this::color).toList()); stack.setItemMeta(meta); return stack; }
  private String replace(String text, Map<String,String> values) { String out = text == null ? "" : text; for (var e : values.entrySet()) out = out.replace("%" + e.getKey() + "%", e.getValue()); return out; }
  private String plain(String text) { return text.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]", ""); }
  private String color(String text) { Matcher m = HEX.matcher(text == null ? "" : text); StringBuffer out = new StringBuffer(); while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#" + m.group(1)) .toString())); m.appendTail(out); return ChatColor.translateAlternateColorCodes('&', out.toString()); }
  private void message(Player p, String key, Map<String,String> values) { p.sendMessage(color(replace(getConfig().getString("messages."+key, ""), values).replace("%prefix%", getConfig().getString("messages.prefix", "")))); }
}
