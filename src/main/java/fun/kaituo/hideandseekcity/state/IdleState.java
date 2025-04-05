package fun.kaituo.hideandseekcity.state;

import fun.kaituo.gameutils.game.GameState;
import fun.kaituo.gameutils.util.Misc;
import fun.kaituo.hideandseekcity.HideAndSeekCity;
import fun.kaituo.hideandseekcity.listener.GameTimeSignListener;
import fun.kaituo.hideandseekcity.util.GameCharacter;
import fun.kaituo.hideandseekcity.util.LocationUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Objective;

import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class IdleState implements GameState, Listener {

    public static IdleState INST = new IdleState();
    private IdleState() {}

    private GameTimeSignListener gameTimeSignListener;

    private HideAndSeekCity game;
    private final Set<Integer> taskIds = new HashSet<>();

    public void init() {
        game = HideAndSeekCity.inst();
        gameTimeSignListener = new GameTimeSignListener(game, game.getLoc("gameTimeSign"));
    }

    @Override
    public void enter() {
        Bukkit.getPluginManager().registerEvents(this, game);
        Bukkit.getPluginManager().registerEvents(gameTimeSignListener, game);
        for (Player p : game.getPlayers()) {
            addPlayer(p);
        }
        Objective mainObjective = game.getMainObjective();
        game.clearMainObjective();
        mainObjective.displayName(Component.text("当前角色数量"));
        mainObjective.getScore("§b躲藏者数量").setScore(0);
        mainObjective.getScore("§7搜寻者数量").setScore(0);
    }

    @Override
    public void exit() {
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(gameTimeSignListener);
        for (Player p : game.getPlayers()) {
            removePlayer(p);
        }
        for (Integer taskId : taskIds) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskIds.clear();
    }

    @Override
    public void tick() {}

    @Override
    public void addPlayer(Player player) {
        player.setRespawnLocation(game.getLoc("hub"));
        player.getInventory().addItem(Misc.getMenu());
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, -1, 4, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, -1, 0, false, false));
        player.teleport(game.getLocation());
        game.selectCharacter(player, GameCharacter.HIDER);
    }

    @Override
    public void removePlayer(Player player) {
        player.getInventory().clear();
        player.removePotionEffect(PotionEffectType.RESISTANCE);
        player.removePotionEffect(PotionEffectType.SATURATION);
    }

    @Override
    public void forceStop() {}

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Location location = Objects.requireNonNull(event.getClickedBlock()).getLocation();
        if (LocationUtils.blockLocationEquals(location, Objects.requireNonNull(game.getLoc("startButton")))) {
            for (Player player: game.getPlayers()) {
                if (!game.getSeekers().contains(player) && !game.getHiders().contains(player)) {
                    game.selectCharacter(player, GameCharacter.HIDER);
                }
            }
            if (game.getPlayers().size() < 2) {
                Player player = event.getPlayer();
                player.sendMessage(Component.text("人数不足，无法开始游戏！", NamedTextColor.RED));
                return;
            }
            // select a random player to be seeker
            Set<Player> players = game.getPlayers();
            int index = new Random().nextInt(players.size());
            Player[] playerArray = players.toArray(new Player[0]);
            Player seeker = playerArray[index];
            game.selectCharacter(seeker, GameCharacter.SEEKER);
            seeker.sendMessage(Component.text("你是搜寻者！", NamedTextColor.GOLD));
            for (Player p : players) {
                if (!p.equals(seeker)) {
                    p.sendMessage(Component.text(seeker.getName() + " 被选为搜寻者！", NamedTextColor.GREEN));
                }
            }
            for (Player player: game.getPlayers()) {
                taskIds.addAll(Misc.displayCountdown(player, 5, game));
            }
            taskIds.add(Bukkit.getScheduler().runTaskLater(game,
                    () -> game.setState(RunningState.INST), 5 * 60L).getTaskId());
        }
    }
}
