package fun.kaituo.hideandseekcity.listener;

import fun.kaituo.gameutils.util.AbstractSignListener;
import fun.kaituo.hideandseekcity.HideAndSeekCity;
import fun.kaituo.hideandseekcity.state.IdleState;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerInteractEvent;

public class GameTimeSignListener extends AbstractSignListener {

    private int gameTimeMinutes = 3;
    private final HideAndSeekCity game;

    public GameTimeSignListener(HideAndSeekCity plugin, Location location) {
        super(plugin, location);
        game = plugin;
        lines.set(0, "寻找时间为 " + gameTimeMinutes + " 分钟");
        update();
    }

    @Override
    public void onRightClick(PlayerInteractEvent event) {
        if (!IdleState.INST.equals(game.getState())) return;
        if (gameTimeMinutes < 10) {
            gameTimeMinutes++;
        }
        lines.set(0, "寻找时间为 " + gameTimeMinutes + " 分钟");
        update();
    }

    @Override
    public void onSneakingRightClick(PlayerInteractEvent event) {
        if (!IdleState.INST.equals(game.getState())) return;
        if (gameTimeMinutes > 3) {
            gameTimeMinutes--;
        }
        lines.set(0, "寻找时间为 " + gameTimeMinutes + " 分钟");
        update();
    }

    @Override
    public void update() {
        super.update();
        game.setGameTimeMinutes(gameTimeMinutes);
    }
}
