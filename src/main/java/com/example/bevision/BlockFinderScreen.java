package com.example.bevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Search box + scrollable block list. Click a block to toggle it on or off. */
public class BlockFinderScreen extends Screen {
    private static final int ROW_H = 20;
    private static final int LIST_W = 240;
    private static final int LIST_TOP = 48;

    private static List<Block> allBlocks;

    private final List<Block> filtered = new ArrayList<>();
    private EditBox search;
    private int scroll = 0;

    public BlockFinderScreen() {
        super(Component.literal("Block Finder"));
        if (allBlocks == null) {
            allBlocks = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                if (!b.defaultBlockState().isAir()) allBlocks.add(b);
            }
            allBlocks.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        search = new EditBox(this.font, cx - LIST_W / 2, 22, LIST_W, 18, Component.literal("Search"));
        search.setMaxLength(64);
        search.setHint(Component.literal("Search blocks (e.g. diamond ore)..."));
        search.setResponder(s -> refilter());
        this.addRenderableWidget(search);
        this.setInitialFocus(search);

        this.addRenderableWidget(Button.builder(Component.literal("Clear all"), b -> BlockFinder.clear())
                .bounds(cx - LIST_W / 2, this.height - 28, 118, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx + 2, this.height - 28, 118, 20).build());

        refilter();
    }

    private void refilter() {
        filtered.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        String qId = q.replace(' ', '_');
        for (Block b : allBlocks) {
            if (q.isEmpty()
                    || b.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BuiltInRegistries.BLOCK.getKey(b).getPath().contains(qId)) {
                filtered.add(b);
            }
        }
        scroll = 0;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - 46 - LIST_TOP) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, filtered.size() - visibleRows());
    }

    private void centered(GuiGraphicsExtractor graphics, Component text, int centerX, int y, int color) {
        graphics.text(this.font, text, centerX - this.font.width(text) / 2, y, color, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta); // background, search box, buttons

        centered(graphics, Component.literal("Block Finder"), this.width / 2, 8, 0xFFFFFFFF);

        int x = this.width / 2 - LIST_W / 2;
        int rows = visibleRows();

        for (int r = 0; r < rows; r++) {
            int idx = scroll + r;
            if (idx >= filtered.size()) break;

            Block b = filtered.get(idx);
            int y = LIST_TOP + r * ROW_H;
            boolean selected = BlockFinder.has(b);
            boolean hover = mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + ROW_H - 1;

            int bg = selected ? 0xA040A040 : (hover ? 0x80808080 : 0x70000000);
            graphics.fill(x, y, x + LIST_W, y + ROW_H - 1, bg);
            graphics.item(new ItemStack(b.asItem()), x + 2, y + 1);
            graphics.text(this.font, b.getName(), x + 24, y + 6, selected ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        }

        if (filtered.isEmpty()) {
            centered(graphics, Component.literal("No blocks match"), this.width / 2, LIST_TOP + 10, 0xFFAAAAAA);
        }

        String footer = BlockFinder.selectedCount() + " selected";
        if (BlockFinder.isCapped()) {
            footer += "  (too many matches, showing the nearest " + BlockFinder.MAX_HITS + ")";
        }
        centered(graphics, Component.literal(footer), this.width / 2, this.height - 42, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int x = this.width / 2 - LIST_W / 2;
        int rows = visibleRows();

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && mx >= x && mx < x + LIST_W
                && my >= LIST_TOP && my < LIST_TOP + rows * ROW_H) {
            int idx = scroll + (int) ((my - LIST_TOP) / ROW_H);
            if (idx >= 0 && idx < filtered.size()) {
                BlockFinder.toggle(filtered.get(idx));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY) * 2));
        return true;
    }
}
