package fun.kaituo.hideandseekcity;

import fun.kaituo.gameutils.GameUtils;
import fun.kaituo.hideandseek.HideAndSeekGame;
import fun.kaituo.hideandseek.state.IdleState;
import fun.kaituo.hideandseek.state.RunningState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

public class HideAndSeekCity extends HideAndSeekGame {

    private IdleState idleState;
    private RunningState runningState;

    @Override
    public IdleState getIdleState() {
        return idleState;
    }

    @Override
    public RunningState getRunningState() {
        return runningState;
    }

    @Override
    public List<Material> getDisguiseMaterials() {
        return List.of(
                Material.SPRUCE_PLANKS,
                Material.ANVIL,
                Material.BEACON,
                Material.DARK_PRISMARINE,
                Material.OAK_LEAVES
        );
    }

    @Override
    protected void initLocations() {
        World world = GameUtils.inst().getMainWorld();
        saveLoc("mapSpawn", new Location(world, -2036.5, 57.0, -20.5));
        saveLoc("waitingRoom", new Location(world, -2021.5, 64.0, 21.5));
        saveLoc("gameTimeSign", new Location(world, -2006, 89, 0));
        saveLoc("hub", new Location(world, -2000, 89, 0));
        saveLoc("startButton", new Location(world, -2006, 90, 1));
    }

    @Override
    protected void initGameStates() {
        idleState = new IdleState();
        runningState = new RunningState();
        idleState.init(this);
        runningState.init(this);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        updateExtraInfo("§b方块捉迷藏-城市", getLoc("hub"));
    }
}
