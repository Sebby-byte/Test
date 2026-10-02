package com.example.bevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

/** Search box + scrollable block list. Click a block to toggle it on or off. */
public class BlockFinderScreen extends Screen {
    private static final int ROW_H = 20;
    private static final int LIST_W = 240;
    private static final int LIST_TOP = 48;

    private static List<Block> allBlocks;

    private final List<Block> filtered = new ArrayList<>();
    private TextFieldWidget search;
    private int scroll = 0;

    public BlockFinderScreen() {
        super(Text.literal("Block Finder"));
        if (allBlocks == null) {
            allBlocks = new ArrayList<>();
            for (Block b : Registries.BLOCK) {
                if (!b.getDefaultState().isAir()) allBlocks.add(b);
            }
            allBlocks.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        search = new TextFieldWidget(this.textRenderer, cx - LIST_W / 2, 22, LIST_W, 18, Text.literal("Search"));
        search.setMaxLength(64);
        search.setPlaceholder(Text.literal("Search blocks (e.g. diamond ore)..."));
        search.setChangedListener(s -> refilter());
        this.addDrawableChild(search);
        this.setInitialFocus(search);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> BlockFinder.clear())
                .dimensions(cx - LIST_W / 2, this.height - 28, 118, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 2, this.height - 28, 118, 20).build());

        refilter();
    }

    private void refilter() {
        filtered.clear();
        String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        String qId = q.replace(' ', '_');
        for (Block b : allBlocks) {
            if (q.isEmpty()
                    || b.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || Registries.BLOCK.getId(b).getPath().contains(qId)) {
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // background + search box + buttons

        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Block Finder"), this.width / 2, 8, 0xFFFFFFFF);

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
            ctx.fill(x, y, x + LIST_W, y + ROW_H - 1, bg);
            ctx.drawItem(new ItemStack(b.asItem()), x + 2, y + 1);
            ctx.drawTextWithShadow(this.textRenderer, b.getName(), x + 24, y + 6,
                    selected ? 0xFFFFFF55 : 0xFFFFFFFF);
        }

        if (filtered.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No blocks match"),
                    this.width / 2, LIST_TOP + 10, 0xFFAAAAAA);
        }

        String footer = BlockFinder.selectedCount() + " selected";
        if (BlockFinder.isCapped()) footer += "  (too many matches, showing the nearest 4000)";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(footer),
                this.width / 2, this.height - 42, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.width / 2 - LIST_W / 2;
        int rows = visibleRows();

        if (button == 0 && mouseX >= x && mouseX < x + LIST_W
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + rows * ROW_H) {
            int idx = scroll + (int) ((mouseY - LIST_TOP) / ROW_H);
            if (idx >= 0 && idx < filtered.size()) {
                BlockFinder.toggle(filtered.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(verticalAmount) * 2));
        return true;
    }
}
