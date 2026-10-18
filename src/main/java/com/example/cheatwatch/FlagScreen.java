package com.example.cheatwatch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The menu that lists every flagged player and what they are flagged for. */
public class FlagScreen extends Screen {
    private static final String[] SORT_NAMES = {"Suspicion", "Closest", "Reach"};
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, RED = 0xFFFF5555,
            ORANGE = 0xFFFFAA00, GREEN = 0xFF55FF55, GOLD = 0xFFFFD700;

    private final CheatWatchClient mod;
    private List<Track> rows = new ArrayList<>();
    private int sort = 0;
    private int page = 0;
    private UUID selected;
    private int refresh = 0;

    public FlagScreen(CheatWatchClient mod) {
        super(Component.literal("fasd's Anti Cheat"));
        this.mod = mod;
    }

    @Override
    protected void init() {
        rows = mod.snapshot(sort);
        int perPage = Math.max(3, Math.min(10, (this.height - 36 - 64) / 22));
        int maxPage = Math.max(0, (rows.size() - 1) / perPage);
        page = Mth.clamp(page, 0, maxPage);

        int left = 20, top = 36;
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= rows.size()) break;
            final Track t = rows.get(idx);
            String label = sort == 2
                    ? String.format("%s  %.2f b", t.name, t.maxReach)
                    : String.format("%s  %.1f", t.name, t.score());
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                selected = t.uuid;
            }).bounds(left, top + i * 22, 190, 20).build());
        }

        int pageY = top + perPage * 22 + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(left, pageY, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(left + 150, pageY, 40, 20).build());

        int by = this.height - 26;
        int bx = this.width / 2 - 209;
        addRenderableWidget(Button.builder(Component.literal("Sort: " + SORT_NAMES[sort]), b -> {
            sort = (sort + 1) % SORT_NAMES.length;
            page = 0;
            rebuildWidgets();
        }).bounds(bx, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear flags"), b -> {
            mod.clearAll();
            selected = null;
            rebuildWidgets();
        }).bounds(bx + 106, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Print to chat"), b -> {
            mod.report(Minecraft.getInstance());
        }).bounds(bx + 212, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(bx + 318, by, 100, 20).build());
    }

    @Override
    public void tick() {
        if (++refresh >= 20) {
            refresh = 0;
            rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);

        g.drawString(this.font, "fasd's Anti Cheat - flagged players", 20, 12, GOLD);

        // closest flagged player
        Track closest = null;
        for (Track t : rows) {
            if (t.score() >= CheatWatchClient.REPORT_VL && mod.now() - t.lastSeen < 40
                    && (closest == null || t.dist < closest.dist)) closest = t;
        }
        if (closest != null) {
            g.drawString(this.font, String.format("Closest suspect: %s (%.1f blocks)", closest.name, closest.dist), 20, 24, ORANGE);
        }

        int x = 235, y = 36;
        if (rows.isEmpty()) {
            g.drawString(this.font, "No flags yet.", 20, 40, GRAY);
            g.drawString(this.font, "Flags appear as nearby players are watched.", 20, 52, GRAY);
        }

        Track sel = null;
        for (Track t : rows) if (t.uuid != null && t.uuid.equals(selected)) sel = t;
        if (sel == null) {
            g.drawString(this.font, "Click a player on the left to see their flags.", x, y, GRAY);
        } else {
            boolean here = mod.now() - sel.lastSeen < 40;
            g.drawString(this.font, sel.name, x, y, WHITE);
            y += 12;
            g.drawString(this.font, String.format("%s | ping %d ms", here ? String.format("%.1f blocks away", sel.dist) : "out of range", sel.ping), x, y, GRAY);
            y += 12;
            int verdictCol = sel.kindsOver(CheatWatchClient.REPORT_VL) >= 2 ? RED
                    : sel.kindsOver(CheatWatchClient.REPORT_VL) == 1 ? ORANGE : GREEN;
            g.drawString(this.font, "Verdict: " + sel.verdict(CheatWatchClient.REPORT_VL), x, y, verdictCol);
            y += 16;
            for (Map.Entry<Cheat, Double> e : sel.vl.entrySet()) {
                if (e.getValue() < 0.5) continue;
                boolean over = e.getValue() >= CheatWatchClient.REPORT_VL;
                g.drawString(this.font, String.format("%s  (vl %.1f)", e.getKey().label, e.getValue()), x, y, over ? RED : GRAY);
                y += 11;
                g.drawString(this.font, "   " + sel.detail.getOrDefault(e.getKey(), e.getKey().desc), x, y, GRAY);
                y += 14;
            }
            if (sel.reachHits > 0) {
                y += 4;
                g.drawString(this.font, String.format("Longest hit seen: %.2f blocks (%d hits tracked)", sel.maxReach, sel.reachHits), x, y, WHITE);
            }
        }

        g.drawString(this.font, "Rebind keys: Options > Controls > Key Binds > fasd's Anti Cheat", 20, this.height - 37, GRAY);
    }
}
