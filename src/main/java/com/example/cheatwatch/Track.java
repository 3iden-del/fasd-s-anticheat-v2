package com.example.cheatwatch;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Everything we remember about one other player. */
public final class Track {
    UUID uuid;
    String name = "?";
    int born;
    boolean has;
    double px, py, pz;
    int lastTeleport = -1000;
    int lastSeen;
    double dist;
    int ping;

    // exemption timers (tick numbers when the thing last happened)
    int lastHurt = -1000, lastSpecial = -1000, lastWater = -1000, lastSlip = -1000, lastBounce = -1000;

    // speed window
    final double[] win = new double[10];
    int winI, winN, speedStrikes, lastStrike = -1000;

    // fly / jump
    boolean wasGround = true;
    double airStartY;
    boolean flaggedJump;
    int hoverTicks, ascendTicks, jesusTicks;

    // step
    final double[] yRing = new double[4];
    int ringI, groundRun;

    // noslow
    int useTicks;
    double useSum;

    // nofall
    boolean airborne, fallInvalid;
    double fallPeak, nfDrop;
    int nfDue;

    // combat
    boolean prevSwinging;
    int prevSwingTime;
    int prevHurtTime;
    float lastHeadYaw;
    final int[] swingTicks = new int[16];
    int swingI, swingCount;
    double maxReach, lastReach;
    int reachHits;

    // pending knockback test (when this player is the victim)
    int kbDue;
    double kbSx, kbSz, kbDx, kbDz;

    // violation levels
    final Map<Cheat, Double> vl = new EnumMap<>(Cheat.class);
    final Map<Cheat, Integer> lastFlag = new EnumMap<>(Cheat.class);
    final Map<Cheat, String> detail = new EnumMap<>(Cheat.class);

    public String name() { return name; }

    public double score() {
        double s = 0;
        for (double v : vl.values()) s += v;
        return s;
    }

    public int kindsOver(double threshold) {
        int n = 0;
        for (double v : vl.values()) if (v >= threshold) n++;
        return n;
    }

    public String verdict(double threshold) {
        int k = kindsOver(threshold);
        if (k >= 3) return "VERY LIKELY hacking";
        if (k == 2) return "LIKELY hacking";
        if (k == 1) return "suspicious";
        return "minor flags";
    }

    void resetMovement() {
        winN = 0;
        hoverTicks = ascendTicks = jesusTicks = 0;
        groundRun = 0;
        useTicks = 0;
        useSum = 0;
        wasGround = true;
        flaggedJump = false;
    }
}
