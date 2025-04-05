package fun.kaituo.hideandseekcity.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public enum GameCharacter {
    HIDER(NamedTextColor.AQUA, "躲藏者"),
    SEEKER(NamedTextColor.DARK_GRAY, "搜寻者");

    public final TextColor textColor;
    public final String displayName;

    GameCharacter(TextColor textColor, String displayName) {
        this.textColor = textColor;
        this.displayName = displayName;
    }
}
