package fun.kaituo.hideandseekcity;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import fun.kaituo.gameutils.GameUtils;
import fun.kaituo.gameutils.game.Game;
import fun.kaituo.hideandseekcity.state.IdleState;
import fun.kaituo.hideandseekcity.state.RunningState;
import fun.kaituo.hideandseekcity.util.GameCharacter;
import fun.kaituo.hideandseekcity.util.HiderData;
import fun.kaituo.hideandseekcity.util.RejoinData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.function.Consumer;

import static net.kyori.adventure.text.format.NamedTextColor.*;

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
    private final HashMap<UUID, Integer> killCountMap = new HashMap<>();
    private Team hidersTeam;
    private Team seekersTeam;

    public Scoreboard getGameScoreboard() {
        return gameScoreboard;
    }

    public Objective getMainObjective() {
        return mainObjective;
    }

    public void clearMainObjective() {
        for (String entry : gameScoreboard.getEntries()) {
            mainObjective.getScore(entry).resetScore();
        }
    }

    public Objective getSneakTimeObjective() {
        return sneakTimeObjective;
    }

    public HashMap<UUID, Integer> getKillCountMap() {
        return killCountMap;
    }

    public void clearTeams() {
        if (hidersTeam != null) {
            hidersTeam.removeEntries(hidersTeam.getEntries());
        }
        if (seekersTeam != null) {
            seekersTeam.removeEntries(seekersTeam.getEntries());
        }
    }

    public static final int TAUNT_REDUCE_SECONDS = 3;
    public static final int TAUNT_COOLDOWN_SECONDS = 30;

    public static final ItemStack tauntItem = createItem(Material.GOLD_NUGGET, m -> {
        m.displayName(Component.text("嘲讽", YELLOW));
        m.lore(
                Arrays.asList(
                        Component.join(
                                JoinConfiguration.noSeparators(),
                                Component.text("效果: 自己所在位置发出声音以及粒子效果，寻找时间减"),
                                Component.text(TAUNT_REDUCE_SECONDS),
                                Component.text("秒")
                        ).color(DARK_PURPLE),
                        Component.join(
                                JoinConfiguration.noSeparators(),
                                Component.text("CD: "),
                                Component.text(TAUNT_COOLDOWN_SECONDS),
                                Component.text("秒")
                        ).color(DARK_PURPLE)
                )
        );
    });

    public static final int SOUND_COOLDOWN_SECONDS = 30;

    public static final ItemStack soundItem = createItem(Material.AMETHYST_SHARD, m -> {
        m.displayName(Component.text("发声", RED));
        m.lore(
                Arrays.asList(
                        Component.text("效果: 所有躲藏者发出声音", DARK_PURPLE),
                        Component.join(
                                JoinConfiguration.noSeparators(),
                                Component.text("CD: "),
                                Component.text(SOUND_COOLDOWN_SECONDS),
                                Component.text("秒")
                        ).color(DARK_PURPLE)
                )
        );
    });

    public static final ItemStack revengeSwordItem = createItem(Material.WOODEN_SWORD, m -> {
        ((Damageable) m).setDamage(49);
        m.displayName(Component.text("复仇之剑", RED));
        m.lore(
                Arrays.asList(
                        Component.text("向搜寻者复仇吧！", DARK_PURPLE),
                        Component.text("提示：搜寻者死亡后会被牵制3秒", DARK_PURPLE)
                )
        );
    });

    private static ItemStack createItem(Material material, Consumer<ItemMeta> metaEditor) {
        ItemStack itemStack = new ItemStack(material);
        itemStack.editMeta(metaEditor);
        return itemStack;
    }

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
    private final HashMap<UUID, Integer> seekerStaminaMap = new HashMap<>();
    private final HashMap<UUID, RejoinData> rejoinDataMap = new HashMap<>();

    public HashMap<UUID, HiderData> getHiderDataMap() {
        return hiderDataMap;
    }

    public HashMap<UUID, Integer> getSeekerStaminaMap() {
        return seekerStaminaMap;
    }

    public enum MainObjectiveDisplay {
        IDLE,
        PREPARE,
        CATCH
    }

    public void setMainObjectiveDisplay(MainObjectiveDisplay display) {
        switch (display) {
            case IDLE:
                mainObjective.getScore("hider_count").customName(Component.text("躲藏者数量", AQUA));
                mainObjective.getScore("seeker_count").customName(Component.text("搜寻者数量", GRAY));
                break;
            case PREPARE:
                mainObjective.getScore("prepare_time").customName(Component.text("准备躲藏时间"));
                mainObjective.getScore("remaining_hiders").customName(Component.text("剩余躲藏者"));
                break;
            case CATCH:
                mainObjective.getScore("remaining_time").customName(Component.text("剩余时间"));
                mainObjective.getScore("remaining_hiders").customName(Component.text("剩余躲藏者"));
                break;
        }
    }

    private void initScoreboard() {
        gameScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        mainObjective = gameScoreboard.registerNewObjective("game_main", Criteria.DUMMY, Component.text("方块捉迷藏", AQUA));
        mainObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sneakTimeObjective = gameScoreboard.registerNewObjective("sneak_time", Criteria.statistic(Statistic.SNEAK_TIME), Component.empty());
        hidersTeam = gameScoreboard.registerNewTeam("hiders");
        hidersTeam.color(AQUA);
        hidersTeam.prefix(Component.text("[躲藏者] ", AQUA));
        hidersTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        hidersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        hidersTeam.setCanSeeFriendlyInvisibles(false);
        hidersTeam.setAllowFriendlyFire(true);
        seekersTeam = gameScoreboard.registerNewTeam("seekers");
        seekersTeam.color(DARK_GRAY);
        seekersTeam.prefix(Component.text("[搜寻者] ", DARK_GRAY));
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
        for (Player p : getPlayers()) {
            removePlayer(p);
            GameUtils.inst().join(p, GameUtils.inst().getLobby());
        }
        this.state.exit();
        mainObjective.unregister();
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
        seekerStaminaMap.clear();
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
            mainObjective.getScore("hider_count").setScore(hiderIds.size());
            mainObjective.getScore("seeker_count").setScore(seekerIds.size());
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
