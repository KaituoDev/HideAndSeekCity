package fun.kaituo.hideandseekcity.util;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

public final class LocationUtils {
    public static boolean blockLocationEquals(@NotNull Location loc1, @NotNull Location loc2) {
        return loc1.toBlockLocation().equals(loc2.toBlockLocation());
    }
}
