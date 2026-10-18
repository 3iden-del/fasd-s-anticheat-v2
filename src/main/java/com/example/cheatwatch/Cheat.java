package com.example.cheatwatch;

public enum Cheat {
    SPEED("Speed", "moving faster than vanilla allows"),
    FLY("Fly / Hover / Spider", "staying up or climbing in the air with no support"),
    HIGH_JUMP("High Jump", "jumping much higher than vanilla allows"),
    STEP("Step", "walking up blocks that are too tall"),
    JESUS("Jesus / Water Walk", "walking on top of water"),
    NOSLOW("NoSlow", "moving at full speed while eating / blocking / drawing a bow"),
    NOFALL("NoFall", "landing from a big fall without taking damage"),
    VELOCITY("Velocity / Anti-KB", "barely moving when hit (no knockback)"),
    REACH("Reach", "hitting players from too far away"),
    KILLAURA("KillAura / Aimbot", "hitting players behind them or snapping aim"),
    AUTOCLICK("AutoClicker", "swinging at the max visible rate for a long time");

    public final String label;
    public final String desc;

    Cheat(String label, String desc) {
        this.label = label;
        this.desc = desc;
    }
}
