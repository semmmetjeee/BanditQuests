package me.semmmetje.banditquests;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.regex.*;

public final class BanditQuests extends JavaPlugin implements Listener, CommandExecutor {
  private static final Pattern HEX=Pattern.compile("&#([A-Fa-f0-9]{6})");
  private final Map<String,Quest> quests=new LinkedHashMap<>();
  private final Map<UUID,Data> data=new HashMap<>();
  private File players; private YamlConfiguration board;

  record Quest(String id,String type,int goal,int marks,String name,List<String> description,Material icon,Set<String> whitelist,Set<String> blacklist,int minimumChatLength){}
  static final class Data {String day="";List<String> jobs=new ArrayList<>();Map<String,Integer> progress=new HashMap<>();Set<String> claimed=new HashSet<>();}
  record Holder() implements InventoryHolder { @Override public Inventory getInventory(){return null;} }

  @Override public void onEnable(){
    saveDefaultConfig(); saveResource("quests.yml",false); saveResource("gui.yml",false);
    players=new File(getDataFolder(),"players"); players.mkdirs(); reloadAll();
    Objects.requireNonNull(getCommand("saloonboard")).setExecutor(this);
    Objects.requireNonNull(getCommand("banditquests")).setExecutor(this);
    getServer().getPluginManager().registerEvents(this,this);
    getServer().getScheduler().runTaskTimer(this,()->Bukkit.getOnlinePlayers().forEach(player->track(player,"PLAY_MINUTES",null,1)),1200L,1200L);
  }

  private void reloadAll(){
    reloadConfig(); quests.clear();
    YamlConfiguration file=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"quests.yml"));
    ConfigurationSection root=file.getConfigurationSection("quests"); if(root!=null) for(String id:root.getKeys(false)){
      ConfigurationSection section=root.getConfigurationSection(id); if(section==null) continue;
      Set<String> whitelist=values(section,"filters.whitelist","whitelist"), blacklist=values(section,"filters.blacklist","blacklist");
      Material icon=Material.matchMaterial(section.getString("display.icon","PAPER"));
      quests.put(id,new Quest(id,section.getString("type","BLOCK_BREAK").toUpperCase(Locale.ROOT),Math.max(1,section.getInt("goal",1)),Math.max(0,section.getInt("rewards.marks",section.getInt("marks",1))),section.getString("display.name",id),section.getStringList("display.description"),icon==null?Material.PAPER:icon,whitelist,blacklist,Math.max(0,section.getInt("options.minimum-chat-length",0))));
    }
    board=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"gui.yml"));
  }
  private Set<String> values(ConfigurationSection section,String modern,String legacy){List<String> list=section.getStringList(modern);if(list.isEmpty())list=section.getStringList(legacy);return list.stream().map(value->value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());}
  private String day(){return LocalDate.now(ZoneId.of(getConfig().getString("daily.reset-timezone","Europe/Amsterdam"))).toString();}
  private Data get(Player player){Data value=data.computeIfAbsent(player.getUniqueId(),this::load);if(!value.day.equals(day())){value.day=day();List<String> pool=new ArrayList<>(quests.keySet());Collections.shuffle(pool,new Random(player.getUniqueId().hashCode()^value.day.hashCode()));value.jobs=new ArrayList<>(pool.subList(0,Math.min(getConfig().getInt("daily.quests-per-player",3),pool.size())));value.progress.clear();value.claimed.clear();save(player,value);}return value;}
  private Data load(UUID id){YamlConfiguration file=YamlConfiguration.loadConfiguration(new File(players,id+".yml"));Data value=new Data();value.day=file.getString("day","");value.jobs=file.getStringList("jobs");ConfigurationSection progress=file.getConfigurationSection("progress");if(progress!=null)for(String key:progress.getKeys(false))value.progress.put(key,progress.getInt(key));value.claimed.addAll(file.getStringList("claimed"));return value;}
  private void save(Player player,Data value){YamlConfiguration file=new YamlConfiguration();file.set("day",value.day);file.set("jobs",value.jobs);value.progress.forEach((id,amount)->file.set("progress."+id,amount));file.set("claimed",new ArrayList<>(value.claimed));try{file.save(new File(players,player.getUniqueId()+".yml"));}catch(IOException exception){getLogger().warning("Could not save quest data: "+exception.getMessage());}}

  private void track(Player player,String event,String target,int amount){Data value=get(player);boolean changed=false;for(String id:value.jobs){Quest quest=quests.get(id);if(quest==null||value.claimed.contains(id)||!quest.type.equalsIgnoreCase(event)||!matches(quest,target))continue;value.progress.merge(id,amount,Integer::sum);changed=true;}if(changed)save(player,value);}
  private boolean matches(Quest quest,String target){if(target==null)return true;String key=target.toUpperCase(Locale.ROOT);if(!quest.whitelist.isEmpty()&&!quest.whitelist.contains(key))return false;return !quest.blacklist.contains(key);}

  @EventHandler(ignoreCancelled=true) public void onBreak(BlockBreakEvent event){track(event.getPlayer(),"BLOCK_BREAK",event.getBlock().getType().name(),1);}
  @EventHandler(ignoreCancelled=true) public void onPlace(BlockPlaceEvent event){track(event.getPlayer(),"BLOCK_PLACE",event.getBlockPlaced().getType().name(),1);}
  private void trackChat(Player player,int length){Data value=get(player);boolean changed=false;for(String id:value.jobs){Quest quest=quests.get(id);if(quest==null||value.claimed.contains(id)||!quest.type.equals("CHAT")||length<quest.minimumChatLength)continue;value.progress.merge(id,1,Integer::sum);changed=true;}if(changed)save(player,value);}
  @EventHandler(ignoreCancelled=true) public void onChat(AsyncPlayerChatEvent event){int length=event.getMessage().trim().length();Bukkit.getScheduler().runTask(this,()->trackChat(event.getPlayer(),length));}
  @EventHandler(ignoreCancelled=true) public void onFish(PlayerFishEvent event){if(event.getState()==PlayerFishEvent.State.CAUGHT_FISH)track(event.getPlayer(),"FISH",null,1);}
  @EventHandler(ignoreCancelled=true) public void onPlayerKill(PlayerDeathEvent event){Player killer=event.getEntity().getKiller();if(killer!=null)track(killer,"KILL_PLAYER",event.getEntityType().name(),1);}
  @EventHandler(ignoreCancelled=true) public void onMobKill(EntityDeathEvent event){if(event.getEntity() instanceof Player)return;Player killer=event.getEntity().getKiller();if(killer!=null)track(killer,"KILL_MOB",event.getEntityType().name(),1);}
  @EventHandler public void onJoin(PlayerJoinEvent event){get(event.getPlayer());track(event.getPlayer(),"JOIN",null,1);}

  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
    if(command.getName().equalsIgnoreCase("banditquests")){
      if(!sender.hasPermission("banditquests.admin")){if(sender instanceof Player player)message(player,"no-permission",Map.of());return true;}
      if(args.length==1&&args[0].equalsIgnoreCase("reload")){reloadAll();sender.sendMessage(color(getConfig().getString("messages.reload","&aBanditQuests reloaded.")));return true;}
      sender.sendMessage(color("&fUse: &#f4b52b/banditquests reload"));return true;
    }
    if(sender instanceof Player player)open(player);return true;
  }

  private void open(Player player){Data value=get(player);int size=board.getInt("board.size",54);Inventory inventory=Bukkit.createInventory(new Holder(),size,color(board.getString("board.title","Saloon Board")));Material border=material(board.getString("board.border.material","GRAY_STAINED_GLASS_PANE"),Material.GRAY_STAINED_GLASS_PANE);if(board.getBoolean("board.border.enabled",true))for(int slot=0;slot<size;slot++)if(slot<9||slot>=size-9||slot%9==0||slot%9==8)inventory.setItem(slot,item(border,board.getString("board.border.name"," "),List.of()));inventory.setItem(board.getInt("board.info.slot",4),item(material(board.getString("board.info.material","CLOCK"),Material.CLOCK),board.getString("board.info.name","Daily Jobs"),board.getStringList("board.info.lore")));List<Integer> slots=board.getIntegerList("board.quest-slots");for(int index=0;index<value.jobs.size()&&index<slots.size();index++){Quest quest=quests.get(value.jobs.get(index));if(quest==null)continue;int current=Math.min(quest.goal,value.progress.getOrDefault(quest.id,0));boolean claimed=value.claimed.contains(quest.id),complete=current>=quest.goal;String status=board.getString(claimed?"quest.status.claimed":complete?"quest.status.complete":"quest.status.incomplete");Material material=material(board.getString(claimed?"quest.materials.claimed":complete?"quest.materials.complete":"quest.materials.incomplete","PAPER"),quest.icon);Map<String,String> placeholders=Map.of("name",quest.name,"progress",""+current,"goal",""+quest.goal,"marks",""+quest.marks,"status",status,"type",pretty(quest.type));List<String> lore=new ArrayList<>();for(String line:quest.description)lore.add(replace(line,placeholders));for(String line:board.getStringList("quest.lore"))lore.add(replace(line,placeholders));inventory.setItem(slots.get(index),item(material,replace(board.getString("quest.name","%name%"),placeholders),lore));}player.openInventory(inventory);}
  @EventHandler public void click(InventoryClickEvent event){if(!(event.getInventory().getHolder() instanceof Holder)||!(event.getWhoClicked() instanceof Player player))return;event.setCancelled(true);int index=board.getIntegerList("board.quest-slots").indexOf(event.getRawSlot());if(index<0)return;Data value=get(player);if(index>=value.jobs.size())return;Quest quest=quests.get(value.jobs.get(index));if(quest==null)return;if(value.claimed.contains(quest.id)){message(player,"already-claimed",Map.of());return;}if(value.progress.getOrDefault(quest.id,0)<quest.goal){message(player,"not-complete",Map.of());return;}value.claimed.add(quest.id);save(player,value);for(String command:getConfig().getStringList("rewards.commands"))Bukkit.dispatchCommand(Bukkit.getConsoleSender(),replace(command,Map.of("player",player.getName(),"marks",""+quest.marks,"quest",quest.id)));message(player,"claimed",Map.of("marks",""+quest.marks));open(player);}
  private Material material(String value,Material fallback){Material material=Material.matchMaterial(value);return material==null?fallback:material;}
  private ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(color(name));meta.setLore(lore.stream().map(this::color).toList());stack.setItemMeta(meta);return stack;}
  private String pretty(String type){return type.toLowerCase(Locale.ROOT).replace('_',' ');}
  private String replace(String text,Map<String,String> values){String output=text==null?"":text;for(var entry:values.entrySet())output=output.replace("%"+entry.getKey()+"%",entry.getValue());return output;}
  private String color(String text){Matcher matcher=HEX.matcher(text==null?"":text);StringBuffer out=new StringBuffer();while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#"+matcher.group(1)).toString()));matcher.appendTail(out);return ChatColor.translateAlternateColorCodes('&',out.toString());}
  private void message(Player player,String key,Map<String,String> values){String text=replace(getConfig().getString("messages."+key,""),values).replace("%prefix%",getConfig().getString("messages.prefix",""));player.sendMessage(color(text));}
}