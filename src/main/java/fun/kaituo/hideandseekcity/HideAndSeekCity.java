package fun.kaituo.hideandseekcity;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import fun.kaituo.gameutils.GameUtils;
import fun.kaituo.gameutils.game.Game;
import fun.kaituo.gameutils.util.ItemStackBuilder;
import fun.kaituo.hideandseekcity.state.RunningState;
import fun.kaituo.hideandseekcity.state.IdleState;
import fun.kaituo.hideandseekcity.util.GameCharacter;
import fun.kaituo.hideandseekcity.util.HiderData;
import fun.kaituo.hideandseekcity.util.RejoinData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.*;

import java.util.*;

public class HideAndSeekCity extends Game implements Listener {

    private static HideAndSeekCity instance;

    public static HideAndSeekCity inst() {
        return instance;
    }

    private ProtocolManager protocolManager;
    private PacketListener equipmentPacketListener;

    public ProtocolManager getProtocolManager() {
        return protocolManager;
    }

    public PacketListener getEquipmentPacketListener() {
        return equipmentPacketListener;
    }

    private int gameTimeMinutes = 3;

    public int getGameTimeMinutes() {
        return gameTimeMinutes;
    }

    public void setGameTimeMinutes(int gameTimeMinutes) {
        this.gameTimeMinutes = gameTimeMinutes;
    }

    private Scoreboard gameScoreboard;
    private Objective mainObjective;
    private Objective sneakTimeObjective;
    private Objective killCountObjective;
    private Team hidersTeam;
    private Team seekersTeam;

    public Scoreboard getGameScoreboard() {
        return gameScoreboard;
    }

    public Objective getMainObjective() {
        return mainObjective;
    }

    public void clearMainObjective() {
        for (String entry: gameScoreboard.getEntries()) {
            mainObjective.getScore(entry).resetScore();
        }
    }

    public Objective getSneakTimeObjective() {
        return sneakTimeObjective;
    }

    public Objective getKillCountObjective() {
        return killCountObjective;
    }

    public void clearTeams() {
        if (hidersTeam != null) {
            hidersTeam.removeEntries(hidersTeam.getEntries());
        }
        if (seekersTeam != null) {
            seekersTeam.removeEntries(seekersTeam.getEntries());
        }
    }

    public static final ItemStack[] disguiseBlockItems = new ItemStack[] {
        new ItemStack(Material.SPRUCE_PLANKS),
                new ItemStack(Material.ANVIL),
                new ItemStack(Material.BEACON),
                new ItemStack(Material.DARK_PRISMARINE),
                new ItemStack(Material.OAK_LEAVES)
    };
    // TODO: switch to game inv setup
    // TODO: add armor to seekers (iron helmet & boots)
    // TODO: design better taunts (maybe 30-60s of cooldown?)
    public static final ItemStack tauntItem = new ItemStackBuilder(Material.GOLD_NUGGET).setDisplayName("§r§e嘲讽").setLore("§r§5效果: 自己所在位置发出声音以及粒子效果，寻找时间减3秒", "§r§5CD: 15秒").build();
    public static final ItemStack soundItem = new ItemStackBuilder(Material.AMETHYST_SHARD).setDisplayName("§r§c发声").setLore("§r§5效果: 所有躲藏者发出声音", "§r§5CD: 30秒").build();
    public static final ItemStack seekerWeaponItem = new ItemStackBuilder(Material.IRON_SWORD).setUnbreakable(true).build();

    private UUID gameUUID;

    public void generateNewGameUUID() {
        gameUUID = UUID.randomUUID();
    }

    public UUID getGameUUID() {
        return gameUUID;
    }

    private final Set<UUID> playerIds = new HashSet<>();
    private final Set<UUID> seekerIds = new HashSet<>();
    private final Set<UUID> hiderIds = new HashSet<>();
    private final HashMap<UUID, HiderData> hiderDataMap = new HashMap<>();
    private final HashMap<UUID, RejoinData> rejoinDataMap = new HashMap<>();

    public HashMap<UUID, HiderData> getHiderDataMap() {
        return hiderDataMap;
    }

    private void initScoreboard() {
        gameScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        mainObjective = gameScoreboard.registerNewObjective("game_main", Criteria.DUMMY, Component.text("方块捉迷藏", NamedTextColor.AQUA));
        mainObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sneakTimeObjective = gameScoreboard.registerNewObjective("sneak_time", Criteria.statistic(Statistic.SNEAK_TIME), Component.text("Sneak Time"));
        killCountObjective = gameScoreboard.registerNewObjective("player_kills", Criteria.statistic(Statistic.PLAYER_KILLS), Component.text("Kills"));
        hidersTeam = gameScoreboard.registerNewTeam("hiders");
        hidersTeam.color(NamedTextColor.AQUA);
        hidersTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        hidersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        hidersTeam.setCanSeeFriendlyInvisibles(false);
        hidersTeam.setAllowFriendlyFire(true);
        seekersTeam = gameScoreboard.registerNewTeam("seekers");
        seekersTeam.color(NamedTextColor.DARK_GRAY);
        seekersTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        seekersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        seekersTeam.setCanSeeFriendlyInvisibles(false);
        seekersTeam.setAllowFriendlyFire(true);
    }

    private void initLocations() {
        World world = GameUtils.inst().getMainWorld();
        saveLoc("mapSpawn", new Location(world, -2036.5, 57.0, -20.5));
        saveLoc("waitingRoom", new Location(world, -2021.5, 64.0, 21.5));
        saveLoc("gameTimeSign", new Location(world, -2006, 89, 0));
        saveLoc("hub", new Location(world, -2000, 89, 0));
        saveLoc("startButton", new Location(world, -2006, 90, 1));
        saveLoc("seekerSelectSign", new Location(world, -2003, 90, 4));
        saveLoc("hiderSelectSign", new Location(world, -2003, 90, -3));
    }

    private void initGameStates() {
        IdleState.INST.init();
        RunningState.INST.init();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        instance = this;

        saveDefaultConfig();
        initLocations();
        updateExtraInfo("§b方块捉迷藏-城市", getLoc("hub"));

        protocolManager = ProtocolLibrary.getProtocolManager();
        equipmentPacketListener = new PacketAdapter(
                this,
                ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_EQUIPMENT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (state == IdleState.INST) return;
                PacketContainer packet = event.getPacket();
                if (!getSeekers().contains(event.getPlayer())) return;  // not a seeker receiving this packet
                int entityId = packet.getIntegers().read(0);
                List<Integer> hiderIds = getHiders().stream().map(Entity::getEntityId).toList();
                if (!hiderIds.contains(entityId)) return;
                packet.getSlotStackPairLists().modify(0, equipments -> {
                    for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipments) {
                        ItemStack itemStack = new ItemStack(Material.AIR);
                        pair.setSecond(itemStack);
                    }
                    return equipments;
                });
            }
        };

        initScoreboard();
        initGameStates();

        setState(IdleState.INST);
    }

    @Override
    public void onDisable() {
        for (Player p: getPlayers()) {
            removePlayer(p);
            GameUtils.inst().join(p, GameUtils.inst().getLobby());
        }
        this.state.exit();
        mainObjective.unregister();
        killCountObjective.unregister();
        sneakTimeObjective.unregister();
        hidersTeam.unregister();
        seekersTeam.unregister();
        Bukkit.getScheduler().cancelTasks(this);
        super.onDisable();
    }

    private Set<Player> convertUUIDsToPlayers(Set<UUID> playerIds) {
        Set<Player> players = new HashSet<>();
        for (UUID id : playerIds) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                players.add(p);
            }
        }
        return players;
    }

    public Set<UUID> getHiderIds() {
        return hiderIds;
    }

    public Set<Player> getPlayers() {
        return convertUUIDsToPlayers(playerIds);
    }

    public Set<Player> getHiders() {
        return convertUUIDsToPlayers(hiderIds);
    }

    public Set<Player> getSeekers() {
        return convertUUIDsToPlayers(seekerIds);
    }

    public void clearPlayerMaps() {
        seekerIds.clear();
        hiderIds.clear();
        hiderDataMap.clear();
        rejoinDataMap.clear();
    }

    public void selectCharacter(Player p, GameCharacter character) {
        if (!playerIds.contains(p.getUniqueId())) {
            addPlayer(p);
        }
        hiderIds.remove(p.getUniqueId());
        seekerIds.remove(p.getUniqueId());
        switch (character) {
            case HIDER -> {
                hiderIds.add(p.getUniqueId());
                hidersTeam.addPlayer(p);
            }
            case SEEKER -> {
                seekerIds.add(p.getUniqueId());
                seekersTeam.addPlayer(p);
            }
        }
        if (state == IdleState.INST) {
            mainObjective.getScore("§b躲藏者数量").setScore(hiderIds.size());
            mainObjective.getScore("§7搜寻者数量").setScore(seekerIds.size());
        }
    }

    @Override
    public void addPlayer(Player p) {
        playerIds.add(p.getUniqueId());
        p.setScoreboard(gameScoreboard);
        if (rejoinDataMap.containsKey(p.getUniqueId())) {
            if (gameUUID.equals(rejoinDataMap.get(p.getUniqueId()).gameUUID)) {
                RejoinData rejoinData = rejoinDataMap.get(p.getUniqueId());
                p.teleport(rejoinData.location);
                rejoinData.inventory.apply(p);
                switch (rejoinData.character) {
                    case HIDER -> {
                        hiderIds.add(p.getUniqueId());
                        hidersTeam.addPlayer(p);
                    }
                    case SEEKER -> {
                        seekerIds.add(p.getUniqueId());
                        seekersTeam.addPlayer(p);
                    }
                }
            }
            rejoinDataMap.remove(p.getUniqueId());
        }
        super.addPlayer(p);
    }

    @Override
    public void removePlayer(Player p) {
        if (playerIds.contains(p.getUniqueId()) && state == RunningState.INST) {
            GameCharacter character;
            if (seekerIds.contains(p.getUniqueId())) {
                character = GameCharacter.SEEKER;
            } else {
                character = GameCharacter.HIDER;
            }
            RejoinData rejoinData = new RejoinData(p, character);
            rejoinDataMap.put(p.getUniqueId(), rejoinData);
        }
        playerIds.remove(p.getUniqueId());
        hiderIds.remove(p.getUniqueId());
        seekerIds.remove(p.getUniqueId());
        hidersTeam.removePlayer(p);
        seekersTeam.removePlayer(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        super.removePlayer(p);
    }
}
