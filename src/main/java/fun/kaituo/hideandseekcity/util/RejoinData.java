package fun.kaituo.hideandseekcity.util;

import fun.kaituo.gameutils.util.GameInventory;
import fun.kaituo.hideandseekcity.HideAndSeekCity;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public class RejoinData {
    public final UUID gameUUID;
    public final Location location;
    public final GameInventory inventory;
    public final GameCharacter character;

    public RejoinData(Player p, GameCharacter character) {
        gameUUID = HideAndSeekCity.inst().getGameUUID();
        location = p.getLocation();
        inventory = new GameInventory(p);
        this.character = character;
    }
}
