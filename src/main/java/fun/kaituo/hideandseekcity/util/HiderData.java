package fun.kaituo.hideandseekcity.util;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

public class HiderData {
    public Material disguiseMaterial;
    public UUID fakeBlockDisplayId;
    public boolean disguised;
    public Location previousLocation;
    public Material replacedBlockMaterial;
}
