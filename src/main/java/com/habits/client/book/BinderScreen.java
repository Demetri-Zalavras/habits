package com.habits.client.book;

import com.habits.progression.SigilType;
import com.habits.core.Rarity;
import com.habits.Habits;
import com.habits.core.CardItems;
import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.Leveling;
import com.habits.core.Payloads;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.progression.CharmData;
import com.habits.progression.ProgPayloads;
import com.habits.progression.ProgRegistries;
import com.habits.progression.ProgressionApi;
import com.habits.progression.SigilData;
import com.habits.progression.SigilItem;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The binder: a leather book with two paper pages of 2 x 2 cards and category tabs on the right edge. Owner: BOOK-UI.
 * <p>Everything is drawn on one grid of "card pixels" (the card textures use the same grid), scaled by a whole number of
 * screen pixels per card px, so the cards, text and pixel art stay crisp at every GUI scale.
 * <p>Cards are added by right-clicking them (core); the binder shows the collection, toggles perks, marks NEW cards
 * seen on hover and opens on the newest unseen card.
 * <p>Sigils and charms (progression): a pouch on the left lists the sigils kept in the book (with counts) and the charms
 * the player carries. Sigils drag onto a card's
 * socket row (on the bottom edge of its art); charms drag into the Charms tab, which also rerolls charm lines.
 */
public class BinderScreen extends Screen {
    // layout, card px
    // 2 x 2 cards per page, as big as the page allows: even gaps around and between them, clear of header and page number
    private static final int COLS = 2, PER_PAGE = COLS * COLS, CW = 90, CH = CardRenderer.heightFor(CW), GAP = 11;
    private static final int POCKET_W = 46, POCKET_GAP = 6, LB = 10, PAGE_W = 215, PAGE_H = 312, SPINE = 14, TAB_OUT = 20;
    private static final int BOOK_X = POCKET_W + POCKET_GAP, BOOK_W = LB * 2 + PAGE_W * 2 + SPINE, BOOK_H = PAGE_H + LB * 2;
    private static final int TOTAL_W = BOOK_X + BOOK_W + TAB_OUT, TOTAL_H = BOOK_H;
    private static final int LEFT_PAGE_X = BOOK_X + LB, RIGHT_PAGE_X = LEFT_PAGE_X + PAGE_W + SPINE, PAGE_Y = LB;
    private static final int HEAD_DX = 8, GRID_DX = (PAGE_W - COLS * CW - (COLS - 1) * GAP) / 2, GRID_DY = 26, TAB_H = 26, TAB_STEP = 28;
    private static final int INK = 0xFF4A3423, INK_SOFT = 0xFF8A7355, RULE = 0xFFC9B68E;

    private static final ResourceLocation LEATHER = Habits.id("book/leather"), PAGE_LEFT = Habits.id("book/page_left"),
            PAGE_RIGHT = Habits.id("book/page_right"), RING = Habits.id("book/ring"), TAB = Habits.id("book/tab"),
            SLOT_LOCKED = Habits.id("book/slot_locked"), POCKET = Habits.id("book/pocket"), INV_SLOT = Habits.id("book/inv_slot");
    // sockets: SOCK square, inset ART_IN from the card edge to sit inside the art window; charm slots on the Charms page
    private static final int SOCK = 12, SOCK_GAP = 2, ART_IN = 5, CHARM_SLOT = 32, CHARM_GAP = 7, CHARMS_TAB = 0xD9B84A;
    private static final int POUCH_ROWS = (PAGE_H + LB * 2 - 26) / 18, NOTICE_MS = 2600;
    private static final ResourceLocation ARROW_NEXT = Habits.id("book/arrow_next"), ARROW_NEXT_HI = Habits.id("book/arrow_hover_next"),
            ARROW_PREV = Habits.id("book/arrow_prev"), ARROW_PREV_HI = Habits.id("book/arrow_hover_prev");
    private static final int ARROW_Y = PAGE_Y + PAGE_H - 15, PREV_X = LEFT_PAGE_X + 10, NEXT_X = RIGHT_PAGE_X + PAGE_W - 24;
    static Item tabIcon(Category c) {
        return c.icon();
    }

    /** {@code category} is null on search result pages and on the Titles pages (the null entry in {@link #tabs}). */
    private record Page(@Nullable Category category, List<SkillDef> skills, int total) {}

    private final Supplier<Collection<SkillDef>> defs;
    private final Supplier<HabitsCollection> collection;
    /** The category pages; {@link #shown} is these, or the search results while there is a query. */
    private final List<Page> pages = new ArrayList<>();
    private List<Page> shown = pages;
    private String query = "";
    /** The spread to return to when the search is cleared. */
    private int spreadBefore;
    private final List<Category> tabs = new ArrayList<>();
    private final Map<Category, ItemStack> tabStacks = new EnumMap<>(Category.class);
    private int spread;
    private float s = 1, ox, oy;
    private final BookSeen seen;
    /** NEW badges being faded out after a hover: skill -> fade start (ms). */
    private final Map<ResourceLocation, Long> fading = new java.util.HashMap<>();
    private boolean opened;
    @Nullable private ResourceLocation pulseId;
    private long pulseStart;
    /** Pouch: stored sigil kinds ({@link #SIGIL_CODE} + bucket), refreshed each frame. */
    private final int[] pouch = new int[POUCH_ROWS * 2];
    private int pouchN;
    /** Drag codes: stored sigils from {@link #SIGIL_CODE} (+ bucket), stored charms from {@link #CHARM_CODE} (+ bag index). */
    static final int SIGIL_CODE = 100, CHARM_CODE = 1000;
    /** Pouch code of the entry being dragged (-1 = none) and the pouch entry it left from. */
    int dragSlot = -1;
    private int dragFrom = -1;
    private ItemStack dragStack = ItemStack.EMPTY;
    private final ItemStack charmIcon = new ItemStack(ProgRegistries.CHARM.get());
    /** Charms tab open, and the charm line picked for a reroll (charm slot, line; -1 = none). */
    boolean charms;
    int selCharm = -1, selLine = -1;
    @Nullable private Component notice;
    private long noticeAt;
    private boolean noticeGood;
    /** The sigil kind a shift-click combine just made (pouch code) and when, for its pop. */
    private int sigilPopCode = -1;
    private long sigilPopAt = -10_000;
    /** Dev screenshots: forced mouse position in card px (NaN = real mouse). */
    float debugX = Float.NaN, debugY = Float.NaN;

    public BinderScreen() {
        this(SkillRegistry::all, () -> HabitsApi.collection(net.minecraft.client.Minecraft.getInstance().player), BookSeen.PLAYER);
    }

    /** Custom data sources (dev screenshots). */
    BinderScreen(Supplier<Collection<SkillDef>> defs, Supplier<HabitsCollection> collection, BookSeen seen) {
        super(Component.translatable("book.habits.title"));
        this.defs = defs;
        this.collection = collection;
        this.seen = seen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        var win = minecraft.getWindow();
        int gs = (int) win.getGuiScale();
        int d = gs;
        while (d > 1 && (TOTAL_W * d > win.getWidth() - 8 || TOTAL_H * d > win.getHeight() - 8)) d--;
        s = d / (float) gs;
        ox = Math.round((width - TOTAL_W * s) / 2f * gs) / (float) gs;
        oy = Math.round((height - TOTAL_H * s) / 2f * gs) / (float) gs;
        rebuild();
        if (!opened) {
            opened = true;
            // open straight on the newest unseen card's spread, and pulse it
            ResourceLocation newest = seen.newest();
            for (int pi = 0; newest != null && pi < pages.size(); pi++)
                for (SkillDef sd : pages.get(pi).skills())
                    if (sd.id().equals(newest)) { spread = pi / 2; pulseId = newest; pulseStart = Util.getMillis(); }
        }
    }

    /** Re-read the skill list (also called when skills are re-synced). */
    void rebuild() {
        pages.clear();
        tabs.clear();
        Map<Category, List<SkillDef>> byCat = new EnumMap<>(Category.class);
        for (SkillDef def : defs.get()) // only the player's specializations; off-spec cards stay saved, unshown
            if (def.title().isEmpty() && com.habits.core.Specs.allows(minecraft.player, def)) byCat.computeIfAbsent(def.category(), c -> new ArrayList<>()).add(def);
        for (var e : byCat.entrySet()) {
            List<SkillDef> list = e.getValue();
            // the class itself first, then its exclusive skills, then the base's skills
            list.sort(Comparator.comparing((SkillDef def) -> def.classPassive() ? 0 : def.classId().isPresent() ? 1 : 2)
                    .thenComparing(SkillDef::rarity).thenComparing(def -> def.displayName().getString()));
            tabs.add(e.getKey());
            for (int i = 0; i < list.size(); i += PER_PAGE)
                pages.add(new Page(e.getKey(), List.copyOf(list.subList(i, Math.min(list.size(), i + PER_PAGE))), list.size()));
        }
        List<SkillDef> titles = new ArrayList<>();
        for (SkillDef def : defs.get()) if (def.title().isPresent()) titles.add(def);
        titles.sort(Comparator.comparing(SkillDef::rarity).thenComparing(def -> def.displayName().getString()));
        if (!titles.isEmpty()) {
            tabs.add(null);
            for (int i = 0; i < titles.size(); i += PER_PAGE)
                pages.add(new Page(null, List.copyOf(titles.subList(i, Math.min(titles.size(), i + PER_PAGE))), titles.size()));
        }
        shown = searching() ? searchPages() : pages;
        spread = Math.max(0, Math.min(spread, spreads() - 1));
    }

    private boolean searching() {
        return !query.isBlank();
    }

    /** Every card (owned or not) whose text holds all the query's words, in book order, 4 per page. */
    private List<Page> searchPages() {
        HabitsCollection col = collection.get();
        List<SkillDef> hits = new ArrayList<>();
        for (Page p : pages)
            for (SkillDef d : p.skills()) if (BookSearch.matches(searchText(d, col.get(d.id()).level()) + sigilText(d), query)) hits.add(d);
        List<Page> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i += PER_PAGE)
            out.add(new Page(null, List.copyOf(hits.subList(i, Math.min(hits.size(), i + PER_PAGE))), hits.size()));
        return out;
    }

    /** Name, ability, perk text, how it is found, category and rarity. */
    private static String searchText(SkillDef def, int level) {
        String ability = def.translationKey() + ".ability";
        return String.join("\n", def.displayName().getString(),
                net.minecraft.locale.Language.getInstance().has(ability) ? Component.translatable(ability).getString() : "",
                def.perkLine(level).getString(), def.foundByText().getString(), def.category().displayName().getString(),
                def.rarity().displayName().getString());
    }

    /** The sigils socketed in this card ("Rare Potent Sigil"), so "sigil" or "potent" finds the cards holding them. */
    private String sigilText(SkillDef def) {
        StringBuilder sb = new StringBuilder();
        for (SigilData sd : ProgressionApi.sockets(minecraft.player, def.id())) if (sd != null) sb.append('\n').append(sd.name().getString());
        return sb.toString();
    }

    private void setQuery(String q) {
        charms = false;
        boolean was = searching();
        query = q;
        if (!was && searching()) spreadBefore = spread;
        rebuild();
        spread = searching() ? 0 : was ? Math.max(0, Math.min(spreadBefore, spreads() - 1)) : spread;
    }

    private int spreads() {
        return (shown.size() + 1) / 2;
    }

    // ------------------------------------------------------------------ render

    /** Dev timing: frame times of this screen's render, worst first (HABITS_PROF). */
    static final java.util.List<Long> FRAME_NS = new java.util.ArrayList<>();

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long t0 = System.nanoTime();
        renderBook(g, mouseX, mouseY, partialTick);
        if (CardProf.ON) { g.flush(); FRAME_NS.add(System.nanoTime() - t0); CardProf.frame(System.nanoTime() - t0); }
    }

    @Override
    public void removed() {
        CardProf.dumpFrames();
    }

    private void renderBook(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        HabitsCollection col = collection.get();
        float mx = Float.isNaN(debugX) ? (mouseX - ox) / s : debugX, my = Float.isNaN(debugY) ? (mouseY - oy) / s : debugY;
        List<Component> tooltip = null;

        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale(s, s, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // leather cover, spine, pages, rings
        SpriteBatch.begin(g);
        SpriteBatch.add(LEATHER, BOOK_X, 0, BOOK_W, BOOK_H);
        SpriteBatch.end();
        int spineX = LEFT_PAGE_X + PAGE_W;
        g.fill(spineX, PAGE_Y, spineX + SPINE, PAGE_Y + PAGE_H, 0xFF3A2112);
        g.fill(spineX + SPINE / 2 - 1, PAGE_Y, spineX + SPINE / 2 + 1, PAGE_Y + PAGE_H, 0xFF24130A);
        SpriteBatch.begin(g);
        SpriteBatch.add(PAGE_LEFT, LEFT_PAGE_X, PAGE_Y, PAGE_W, PAGE_H);
        SpriteBatch.add(PAGE_RIGHT, RIGHT_PAGE_X, PAGE_Y, PAGE_W, PAGE_H);
        for (int i = 0; i < 4; i++) SpriteBatch.add(RING, spineX + SPINE / 2 - 12, PAGE_Y + 33 + i * 76, 24, 14);
        SpriteBatch.end();

        // tabs (right edge): the categories, then Charms
        Category current = charms || shown.isEmpty() ? null : shown.get(spread * 2).category(); // null while searching: every tab dims
        Category current2 = !charms && spread * 2 + 1 < shown.size() ? shown.get(spread * 2 + 1).category() : current;
        int tabX = BOOK_X + BOOK_W - LB - 1, tabY0 = tabY0();
        for (int i = 0; i <= tabs.size(); i++) {
            boolean charmTab = i == tabs.size();
            Category c = charmTab ? null : tabs.get(i);
            int color = charmTab ? CHARMS_TAB : c == null ? TITLES_TAB : c.tabColor;
            boolean sel = charmTab ? charms : (c == current || c == current2) && !(c == null && searching());
            int ty = tabY0 + i * TAB_STEP;
            boolean hover = in(mx, my, tabX, ty, LB + 1 + TAB_OUT, TAB_H);
            int out = sel || hover ? TAB_OUT : TAB_OUT - 4;
            CardRenderer.tint(g, sel ? color : darken(color), 1);
            SpriteBatch.begin(g);
            SpriteBatch.add(TAB, tabX, ty, LB + 1 + out, TAB_H);
            SpriteBatch.end();
            g.setColor(1, 1, 1, 1);
            ItemStack icon = charmTab ? charmIcon : c == null ? TITLES_ICON : tabStacks.computeIfAbsent(c, k -> new ItemStack(k.icon()));
            g.renderFakeItem(icon, tabX + (LB + 1 + out - 16) / 2, ty + (TAB_H - 16) / 2); // centered on the whole tab
            if (hover) tooltip = charmTab ? List.of(Component.translatable("book.habits.charms").withColor(CHARMS_TAB))
                    : c == null ? List.of(Component.translatable("book.habits.titles").withColor(TITLES_TAB),
                    Component.translatable("book.habits.count", owned(null), total(null)).withStyle(ChatFormatting.GRAY))
                    : List.of(c.displayName().copy().withColor(c.tabColor),
                    Component.translatable("book.habits.count", owned(c), total(c)).withStyle(ChatFormatting.GRAY));
        }

        // the two pages; card faces are built at most BUILD_BUDGET_NS per frame, the rest on the next frames
        budgetLeft = BUILD_BUDGET_NS;
        for (int side = 0; side < 2 && !charms; side++) {
            int pi = spread * 2 + side;
            int px = side == 0 ? LEFT_PAGE_X : RIGHT_PAGE_X;
            if (searching()) searchHeader(g, px, side);
            if (pi >= shown.size()) {
                if (shown.isEmpty() && side == 0)
                    g.drawCenteredString(font, Component.translatable(searching() ? "book.habits.search_none" : "book.habits.empty"),
                            px + PAGE_W / 2, PAGE_Y + PAGE_H / 2 - 4, INK_SOFT);
                continue;
            }
            Page page = shown.get(pi);
            if (!searching()) header(g, page, px, side);
            for (int i = 0; i < page.skills().size(); i++) {
                SkillDef def = page.skills().get(i);
                int cx = slotX(px, i), cy = slotY(i);
                SkillProgress p = col.get(def.id());
                boolean hover = in(mx, my, cx, cy, CW, CH);
                if (p.level() <= 0) {
                    CardRenderer.blit(g, SLOT_LOCKED, cx, cy, CW, CH);
                    if (hover) tooltip = lockedTooltip(def);
                } else if (ready(def, p)) {
                    if (def.id().equals(pulseId)) pulse(g, def, cx, cy);
                    int lift = hover ? 2 : 0;
                    CardRenderer.render(g, def, p.level(), CardRenderer.Size.FULL, cx, cy - lift, CW,
                            facePx(), 1, true, on(def, p), hover ? (mx - cx) / CW : .5f, hover ? (my - cy) / CH : .3f, hover ? 1 : 0);
                    if (hover) tooltip = cardTooltip(def, p);
                    List<Component> sockTip = socketRow(g, def, p.level(), cx, cy - lift, mx, my);
                    if (sockTip != null) tooltip = sockTip;
                    newBadge(g, def, cx, cy - lift, hover);
                } else {
                    // face not built yet (frame budget spent): a dark card for a frame or two
                    CardPaint.shape(g, cx, cy, cx + CW, cy + CH, 3, 1, 1, CardPaint.solid(0xFF1B1C21));
                }
            }
            String num = String.valueOf(pi + 1);
            g.drawString(font, num, px + (PAGE_W - font.width(num)) / 2, PAGE_Y + PAGE_H - 12, INK_SOFT, false);
        }
        if (charms) {
            List<Component> t = charmsPages(g, col, mx, my);
            if (t != null) tooltip = t;
        } else {
            // page arrows (hit box 2 px larger than the 14 x 9 art)
            if (spread > 0)
                CardRenderer.blit(g, in(mx, my, PREV_X - 2, ARROW_Y - 2, 18, 13) ? ARROW_PREV_HI : ARROW_PREV, PREV_X, ARROW_Y, 14, 9);
            if (spread < spreads() - 1)
                CardRenderer.blit(g, in(mx, my, NEXT_X - 2, ARROW_Y - 2, 18, 13) ? ARROW_NEXT_HI : ARROW_NEXT, NEXT_X, ARROW_Y, 14, 9);
            prewarmNext();
        }

        List<Component> specTip = specsFooter(g, mx, my);
        if (specTip != null) tooltip = specTip;
        List<Component> pouchTip = pocket(g, mx, my);
        if (pouchTip != null) tooltip = pouchTip;
        drawNotice(g);
        if (dragSlot >= 0) {
            tooltip = null;
            g.pose().pushPose();
            g.pose().translate(mx - 8, my - 8, 300);
            g.renderItem(dragStack, 0, 0);
            g.pose().popPose();
        }

        g.pose().popPose();
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, Float.isNaN(debugX) ? mouseX : (int) (ox + debugX * s),
                Float.isNaN(debugY) ? mouseY : (int) (oy + debugY * s));
    }

    private static final long FADE_MS = 350, PULSE_MS = 2400;

    /** Soft glow around the newest card when the book opens on it (drawn behind the card, ~3 slow pulses). */
    private void pulse(GuiGraphics g, SkillDef def, int cx, int cy) {
        float t = (Util.getMillis() - pulseStart) / (float) PULSE_MS;
        if (t >= 1) { pulseId = null; return; }
        float a = (1 - t) * (.55f + .45f * (float) Math.sin(t * Math.PI * 2 * 3 - Math.PI / 2));
        int rc = def.rarity().color;
        float m = 6, x0 = cx, y0 = cy, x1 = cx + CW, y1 = cy + CH;
        CardPaint.shape(g, x0 - m, y0 - m, x1 + m, y1 + m, m + 3, 8, 10, (px, py) -> {
            float ox = Math.max(0, Math.max(x0 - px, px - x1)), oy = Math.max(0, Math.max(y0 - py, py - y1));
            float d = Math.min(1, (float) Math.sqrt(ox * ox + oy * oy) / m);
            return CardPaint.alpha(a * .9f * (1 - d) * (1 - d), rc);
        });
    }

    /**
     * Small shiny "NEW" label on the card's top corner while its skill is unseen. Drawn over the cached face (no
     * rebuild); the first hover tells the server and fades it out.
     */
    private void newBadge(GuiGraphics g, SkillDef def, int cx, int cy, boolean hover) {
        Long fadeAt = fading.get(def.id());
        if (fadeAt == null && !seen.isUnseen(def.id())) return;
        if (hover && fadeAt == null) {
            fadeAt = Util.getMillis();
            fading.put(def.id(), fadeAt);
            seen.markSeen(def.id());
        }
        float a = fadeAt == null ? 1 : 1 - (Util.getMillis() - fadeAt) / (float) FADE_MS;
        if (a <= 0) return;
        // draw in screen pixels so the label text is 1:1 with the bundled font
        float d = CardRenderer.density(g);
        float bh = Math.round(11 * d), bw = Math.round(27 * d);
        float bx = Math.round((cx + (CW - 27) / 2f) * d), by = Math.round((cy - 8) * d); // centered on the top edge, above name and LV
        g.pose().pushPose();
        g.pose().scale(1 / d, 1 / d, 1);
        g.pose().translate(0, 0, 60);
        float r = bh / 2;
        CardPaint.shape(g, bx - d, by - d, bx + bw + d, by + bh + d, r + d, 1, 1, CardPaint.solid(CardPaint.alpha(.85f * a, 0x2A1204)));
        CardPaint.shape(g, bx, by, bx + bw, by + bh, r, 2, 4, CardPaint.linear(bx, by, bx + bw, by + bh, 180,
                new float[]{0, .5f, 1}, new int[]{CardPaint.alpha(a, 0xFFE27A), CardPaint.alpha(a, 0xFFB52E), CardPaint.alpha(a, 0xE8761C)}));
        // shine: a bright band sweeps across every 2.4 s
        float t = (Util.getMillis() % 2400L) / 2400f, band = bw * .35f, c = -band + t * (bw + 2 * band) * 1.6f;
        CardRenderer.additive();
        CardPaint.texGridColor(g, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 10, 1, (px, py) -> {
            float u = (px - bx) + (py - by) * .6f;
            float k = Math.max(0, 1 - Math.abs(u - c) / band);
            return CardPaint.alpha(.75f * k * k * a, 0xFFFFFF);
        });
        RenderSystem.defaultBlendFunc();
        Component label = CardPaint.text(Component.translatable("book.habits.new").getString(), CardPaint.Weight.XB, 9.5f * d);
        int textA = Math.max(4, Math.round(255 * a));
        float tx = bx + (bw - CardPaint.width(label)) / 2f, base = by + bh / 2 + 9.5f * d * .36f;
        CardPaint.draw(g, label, tx, base + Math.max(1, Math.round(d * .6f)), (Math.round(140 * a) << 24) | 0x5A2A00, false);
        CardPaint.draw(g, label, tx, base, (textA << 24) | 0xFFFFFF, false);
        g.pose().popPose();
    }

    /** CPU time per frame the binder may spend painting new card faces (a fresh spread fills in over a few frames). */
    private static final long BUILD_BUDGET_NS = 5_000_000L, PREWARM_BUDGET_NS = 1_500_000L;
    private long budgetLeft;

    private int facePx() {
        int px = Math.round(CW * s * (float) minecraft.getWindow().getGuiScale());
        // the full layout's fonts bottom out at 6 px and overlap under ~160 px: paint at 2x, drawn LINEAR = a 2x2 box filter
        return px < 160 ? px * 2 : px;
    }

    /** Drawn as active: toggled on and in one of the player's specializations. */
    private boolean on(SkillDef def, SkillProgress p) {
        return p.enabled() && com.habits.core.Specs.allows(minecraft.player, def);
    }

    private boolean ready(SkillDef def, SkillProgress p) {
        long t0 = System.nanoTime();
        boolean ok = CardRenderer.prepare(def, p.level(), on(def, p), CardRenderer.Size.FULL, facePx(), budgetLeft);
        budgetLeft -= System.nanoTime() - t0;
        return ok;
    }

    /** Spend what is left of this frame's budget on the next spread, so flipping forward is instant. */
    private void prewarmNext() {
        budgetLeft = Math.min(budgetLeft, PREWARM_BUDGET_NS); // the next spread fills in over more frames, so no frame spikes
        HabitsCollection col = collection.get();
        for (int pi = spread * 2 + 2; pi < Math.min(shown.size(), spread * 2 + 4) && budgetLeft > 0; pi++)
            for (SkillDef def : shown.get(pi).skills()) {
                SkillProgress p = col.get(def.id());
                if (p.level() > 0 && !ready(def, p)) return;
            }
    }

    private void header(GuiGraphics g, Page page, int px, int side) {
        int x0 = px + HEAD_DX, x1 = px + PAGE_W - HEAD_DX, y = PAGE_Y + 7;
        Component title = page.category() == null ? Component.translatable("book.habits.titles") : page.category().displayName();
        g.drawString(font, title, x0, y, INK, false);
        String count = owned(page.category()) + "/" + page.total();
        g.drawString(font, count, x1 - font.width(count), y, INK_SOFT, false);
        // the left page says you can type, between title and count, when it fits
        Component hint = Component.translatable("book.habits.search_hint");
        int free = x1 - font.width(count) - (x0 + font.width(title)), hw = font.width(hint);
        if (side == 0 && free >= hw + 16) g.drawString(font, hint, x0 + font.width(title) + (free - hw) / 2, y, RULE, false);
        g.fill(x0, y + 10, x1, y + 11, RULE);
    }

    /** While searching: the query with a blinking caret on the left page, the match count on the right. */
    private void searchHeader(GuiGraphics g, int px, int side) {
        int x0 = px + HEAD_DX, x1 = px + PAGE_W - HEAD_DX, y = PAGE_Y + 7;
        if (side == 0) {
            Component label = Component.translatable("book.habits.search_title").append(": ");
            g.drawString(font, label, x0, y, INK_SOFT, false);
            String q = query + (Util.getMillis() / 500 % 2 == 0 ? "_" : "");
            g.drawString(font, q, x0 + font.width(label), y, INK, false);
        } else {
            Component found = Component.translatable("book.habits.search_found", shown.isEmpty() ? 0 : shown.get(0).total());
            g.drawString(font, found, x1 - font.width(found), y, INK_SOFT, false);
        }
        g.fill(x0, y + 10, x1, y + 11, RULE);
    }

    private List<Component> cardTooltip(SkillDef def, SkillProgress p) {
        List<Component> lines = new ArrayList<>();
        lines.add(def.displayName().copy().withColor(def.rarity().color));
        lines.add(def.rarity().displayName().copy().withColor(def.rarity().color)
                .append(Component.translatable("book.habits.level", p.level()).withStyle(ChatFormatting.GRAY)));
        lines.add(Component.translatable("book.habits.now", com.habits.core.Forks.apply(minecraft.player, def).perkLine(p.level()))
                .withStyle(on(def, p) ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
        for (SkillDef.ForkStep step : def.forks()) { // hand-made choices at their level; right-click / shift-right-click swaps
            boolean reached = p.level() >= step.level();
            lines.add(Component.translatable("book.habits.fork_at", step.level()).withStyle(reached ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
            SkillDef.ForkOption cur = com.habits.core.Forks.chosen(minecraft.player, def, step);
            for (SkillDef.ForkOption o : step.options()) {
                String k = def.translationKey() + ".fork." + o.id();
                lines.add(Component.literal(reached && o == cur ? " > " : "   ").append(Component.translatable(k))
                        .append(Component.literal(": ")).append(Component.translatable(k + ".desc"))
                        .withStyle(reached && o == cur ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
            }
        }
        if (!def.forks().isEmpty()) lines.add(Component.translatable("book.habits.fork_how").withStyle(ChatFormatting.DARK_GRAY));
        if (!com.habits.core.Specs.allows(minecraft.player, def))
            lines.add(Component.translatable("habits.msg.off_spec", def.category().displayName()).withStyle(ChatFormatting.RED));
        boosts(lines, def, p.level());
        if (p.maxed()) {
            lines.add(Component.translatable("book.habits.maxed").withStyle(ChatFormatting.GOLD));
        } else {
            lines.add(Component.translatable("book.habits.next", def.perkLine(p.level() + 1)).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("book.habits.progress", p.progress(), Leveling.xpToNext(p.level(), def.rarity()), p.level() + 1)
                    .withStyle(ChatFormatting.AQUA));
        }
        lines.add(Component.translatable("book.habits.found_by", def.foundByText()).withStyle(ChatFormatting.DARK_GRAY));
        if (def.title().isPresent()) {
            boolean shown = minecraft.player.getData(com.habits.core.CoreRegistries.SHOWN_TITLE).filter(def.id()::equals).isPresent();
            lines.add(Component.translatable(shown ? "book.habits.title_hide" : "book.habits.title_show").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        lines.add(Component.translatable(p.enabled() ? "book.habits.click_off" : "book.habits.click_on")
                .withStyle(p.enabled() ? ChatFormatting.DARK_GRAY : ChatFormatting.RED));
        return lines;
    }

    /** Green lines for what sigils and charms add to this card (none when nothing does). */
    private void boosts(List<Component> lines, SkillDef def, int level) {
        var pl = minecraft.player;
        double cd = ProgressionApi.cooldownMultiplier(pl, def),
                range = ProgressionApi.rangeMultiplier(pl, def), drop = ProgressionApi.dropMultiplier(pl, def);
        SkillDef b = ProgressionApi.boosted(pl, def); // Swift also changes wait values (last_stand)
        if (b.perkValue(level) != def.perkValue(level)) lines.add(Component.translatable("book.habits.boosted", b.perkLine(level))
                .withStyle(ChatFormatting.GREEN));
        if (cd != 1) lines.add(Component.translatable("sigil.habits.swift.effect", pct(1 - cd)).withStyle(ChatFormatting.GREEN));
        if (range != 1) lines.add(Component.translatable("sigil.habits.reach.effect", pct(range - 1)).withStyle(ChatFormatting.GREEN));
        if (drop != 1) lines.add(Component.translatable("sigil.habits.lucky.effect", pct(drop - 1)).withStyle(ChatFormatting.GREEN));
    }

    private static String pct(double fraction) {
        return SkillDef.format(Math.round(fraction * 1000) / 10.0);
    }

    private List<Component> lockedTooltip(SkillDef def) {
        return List.of(Component.translatable("book.habits.locked").withStyle(ChatFormatting.GRAY),
                def.rarity().displayName().copy().withColor(def.rarity().color),
                Component.translatable("book.habits.found_by", def.foundByText()).withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------ sigils and charms

    /** Card px from the card's top to the bottom of its art window, per skill (the art ends higher on 2-line perks). */
    private static final Map<ResourceLocation, Integer> ART_BOTTOM = new java.util.HashMap<>();
    /** One display stack per sigil kind (4 types x 5 rarities), so sockets draw without a new stack each frame. */
    private static final Map<SigilData, ItemStack> SIGIL_STACKS = new java.util.HashMap<>();
    /** Pouch stacks per sigil kind; their count is set to the stored count when drawn. */
    private static final ItemStack[] POUCH_STACKS = new ItemStack[SigilData.ALL.size()];

    static int sigilCode(SigilData s) {
        return SIGIL_CODE + com.habits.progression.ProgressionData.bucket(s);
    }

    /** The stack a pouch entry shows: a stored sigil kind with its count. */
    private ItemStack pouchStack(int code) {
        SigilData sd = SigilData.ALL.get(code - SIGIL_CODE);
        ItemStack st = POUCH_STACKS[code - SIGIL_CODE];
        if (st == null) st = POUCH_STACKS[code - SIGIL_CODE] = SigilItem.create(sd);
        st.setCount(Math.max(1, ProgressionApi.sigilCount(minecraft.player, sd)));
        return st;
    }

    private static int artBottom(SkillDef def) {
        Integer b = ART_BOTTOM.get(def.id());
        if (b != null) return b;
        CardFace.Entry e = CardFace.LAYOUTS.get(def.id().toString());
        if (e == null) return Math.round(CH * .68f);
        int v = Math.round(e.layout.artY1() * CW / e.w);
        ART_BOTTOM.put(def.id(), v);
        return v;
    }

    private static int socketX(int cx, int i) {
        return cx + ART_IN + 2 + i * (SOCK + SOCK_GAP);
    }

    private static int socketY(SkillDef def, int cy) {
        return cy + artBottom(def) - SOCK - 2;
    }

    @Nullable
    private SigilData dragSigil() {
        return dragSlot < 0 ? null : ProgressionApi.sigilOf(dragStack);
    }

    /** Text at a whole number of screen pixels per font pixel, about 0.6 of the normal size. */
    private void small(GuiGraphics g, String text, float x, float y, int color) {
        float d = CardRenderer.density(g), fs = Math.max(1, Math.round(d * .6f)) / d;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(fs, fs, 1);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private float smallWidth(GuiGraphics g, String text) {
        float d = CardRenderer.density(g);
        return font.width(text) * Math.max(1, Math.round(d * .6f)) / d;
    }

    /**
     * The card's socket row on the bottom edge of its art: filled (the sigil), empty, or locked (a lock and the level
     * that opens it). While a sigil is dragged, the sockets it fits light up and the others grey out. A green number on the
     * right is the boosted perk value. Returns the hovered socket's tooltip.
     */
    @Nullable
    private List<Component> socketRow(GuiGraphics g, SkillDef def, int level, int cx, int cy, float mx, float my) {
        var pl = minecraft.player;
        int open = ProgressionApi.socketCount(level), y = socketY(def, cy);
        SigilData drag = dragSigil();
        boolean fits = drag != null && ProgressionApi.fits(drag.type(), def);
        float pulse = .6f + .4f * (float) Math.sin(Util.getMillis() / 140.0);
        List<Component> tip = null;
        for (int i = 0; i < 4; i++) {
            int lv = ProgressionApi.socketLevel(i);
            if (lv < 0) break;
            int x = socketX(cx, i);
            boolean locked = i >= open, hover = in(mx, my, x, y, SOCK, SOCK);
            SigilData sd = locked ? null : ProgressionApi.sigil(pl, def.id(), i);
            g.fill(x, y, x + SOCK, y + SOCK, 0xE60E0E12);
            g.renderOutline(x, y, SOCK, SOCK, locked ? 0xFF34343B : sd != null ? 0xFF000000 | sd.rarity().color : 0xFF73757F);
            if (sd != null) {
                g.pose().pushPose();
                g.pose().translate(x + 2, y + 2, 0);
                g.pose().scale(.5f, .5f, 1);
                g.renderItem(SIGIL_STACKS.computeIfAbsent(sd, SigilItem::create), 0, 0);
                g.pose().popPose();
            } else if (locked) {
                // padlock (shackle, body) over the level that opens it
                int lc = 0xFF8A8C96;
                g.fill(x + 5, y + 2, x + 7, y + 3, lc);
                g.fill(x + 4, y + 3, x + 5, y + 4, lc);
                g.fill(x + 7, y + 3, x + 8, y + 4, lc);
                g.fill(x + 3, y + 4, x + 9, y + 7, lc);
                String n = String.valueOf(lv);
                small(g, n, x + (SOCK - smallWidth(g, n)) / 2f + .5f, y + 7.5f, 0xFFB8BAC4);
            }
            if (drag != null && !locked) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 200); // over the item
                if (fits) {
                    int c = CardRenderer.mul(0xFFFFE27A, pulse);
                    g.renderOutline(x - 1, y - 1, SOCK + 2, SOCK + 2, c);
                    if (hover) g.fill(x + 1, y + 1, x + SOCK - 1, y + SOCK - 1, 0x60FFFFFF);
                } else {
                    g.fill(x, y, x + SOCK, y + SOCK, 0xC0505058);
                }
                g.pose().popPose();
            }
            if (hover && drag == null) tip = socketTooltip(def, level, sd, locked, lv);
        }
        double m = ProgressionApi.perkMultiplier(pl, def);
        if (m != 1) {
            String v = boostedValue(def, level);
            float w = smallWidth(g, v) + 4, x1 = cx + CW - ART_IN - 2, x0 = x1 - w;
            CardPaint.shape(g, x0, y + 1, x1, y + SOCK - 1, 2, 1, 1, CardPaint.solid(0xE6122516));
            g.renderOutline(Math.round(x0), y + 1, Math.round(w), SOCK - 2, 0xFF3FAF55);
            small(g, v, x0 + 2.5f, y + 3.5f, 0xFF8CFF9C);
        }
        return tip;
    }

    /** The card's big value with sigils and charms, cached per player data and level (the face's own text, boosted). */
    private final Map<ResourceLocation, String> boostedText = new java.util.HashMap<>();
    @Nullable private Object boostedFor;

    private String boostedValue(SkillDef def, int level) {
        Object key = minecraft.player.getData(ProgRegistries.DATA);
        if (key != boostedFor) { boostedText.clear(); boostedFor = key; } // the data is immutable: a change is a new instance
        String v = boostedText.get(def.id());
        if (v == null || !v.startsWith(level + ":")) {
            v = level + ":" + CardFace.Readout.of(ProgressionApi.boosted(minecraft.player, def), level, 0).value();
            boostedText.put(def.id(), v);
        }
        return v.substring(v.indexOf(':') + 1);
    }

    private List<Component> socketTooltip(SkillDef def, int level, @Nullable SigilData sd, boolean locked, int lv) {
        if (locked) return List.of(Component.translatable("book.habits.socket_locked", lv).withStyle(ChatFormatting.GRAY));
        if (sd == null) return List.of(Component.translatable("book.habits.socket_empty"),
                Component.translatable("book.habits.socket_drag").withStyle(ChatFormatting.GRAY));
        List<Component> l = new ArrayList<>();
        l.add(SIGIL_STACKS.computeIfAbsent(sd, SigilItem::create).getHoverName());
        l.add(Component.translatable("sigil.habits." + sd.type().getSerializedName() + ".effect", SkillDef.format(sd.percent()))
                .withStyle(ChatFormatting.GRAY));
        if (sd.type() == com.habits.progression.SigilType.POTENT)
            l.add(Component.translatable("book.habits.on_card", ProgressionApi.boosted(minecraft.player, def).perkLine(level))
                    .withStyle(ChatFormatting.GREEN));
        l.add(Component.translatable("book.habits.socket_take").withStyle(ChatFormatting.DARK_GRAY));
        return l;
    }

    /** Socket index under the mouse on an owned card, or -1. */
    private int socketAt(SkillDef def, int cx, int cy, float mx, float my) {
        int y = socketY(def, cy);
        for (int i = 0; i < 4 && ProgressionApi.socketLevel(i) >= 0; i++)
            if (in(mx, my, socketX(cx, i) - 1, y - 1, SOCK + 2, SOCK + 2)) return i;
        return -1;
    }

    /** Reads the sigil store into {@link #pouch}, grouped by rarity. */
    private void refreshPouch() {
        pouchN = 0;
        headN = 0;
        layY = 18;
        // sigils grouped by rarity, Common first and Legendary last, each under a header in its rarity color (user pick)
        Rarity[] rs = Rarity.values();
        SigilType[] ts = SigilType.values();
        for (int r = 0; r < rs.length; r++) {
            layCol = -1;
            for (SigilType t : ts) {
                SigilData kind = SigilData.of(t, rs[r]);
                if (ProgressionApi.sigilCount(minecraft.player, kind) > 0) place(SIGIL_CODE + kind.bucket(), r);
            }
            if (layCol > 0) layY += 18;
        }
        if (dragSlot >= 0) {
            boolean gone = dragSlot >= CHARM_CODE ? dragSlot - CHARM_CODE >= ProgressionApi.storedCharms(minecraft.player).size()
                    : ProgressionApi.sigilCount(minecraft.player, SigilData.ALL.get(dragSlot - SIGIL_CODE)) <= 0;
            if (gone) dragSlot = -1; // spent or moved meanwhile
        }
    }

    /** Pouch layout state while refreshing: next row y, column in the current group (-1 = group not started). */
    private int layY, layCol;
    private final float[] pouchXs = new float[POUCH_ROWS * 2], pouchYs = new float[POUCH_ROWS * 2];
    /** Group headers: rarity ordinal (-1 = charms) and y. */
    private final int[] headR = new int[8];
    private final float[] headY = new float[8];
    private int headN;
    private static final int HEAD_H = 8;

    /** Adds one pouch entry to group {@code group} (rarity ordinal, -1 = charms), opening the group's header first. */
    private void place(int code, int group) {
        if (layCol < 0) {
            if (layY + HEAD_H + 18 > TOTAL_H - 6 || headN == headR.length) return;
            headR[headN] = group;
            headY[headN++] = layY;
            layY += HEAD_H;
            layCol = 0;
        }
        if (layCol == 2) { layCol = 0; layY += 18; }
        // shortcut: entries past the pouch bottom are not shown; add scrolling if players carry that many kinds
        if (layY + 18 > TOTAL_H - 6 || pouchN == pouch.length) return;
        pouchXs[pouchN] = 5 + layCol * 18;
        pouchYs[pouchN] = layY;
        pouch[pouchN++] = code;
        layCol++;
    }

    private float pouchX(int i) {
        return pouchXs[i];
    }

    private float pouchY(int i) {
        return pouchYs[i];
    }

    private int pouchAt(float mx, float my) {
        for (int i = 0; i < pouchN; i++) if (in(mx, my, pouchX(i), pouchY(i), 18, 18)) return i;
        return -1;
    }

    /** The leather pouch on the left with the sigils kept in the book. Returns the hovered entry's tooltip. */
    @Nullable
    private List<Component> pocket(GuiGraphics g, float mx, float my) {
        refreshPouch();
        CardRenderer.blit(g, POCKET, 0, 0, POCKET_W, TOTAL_H);
        g.drawCenteredString(font, Component.translatable("book.habits.pouch"), POCKET_W / 2, 6, 0xFFE8D2A6);
        List<Component> tip = null;
        float hd = CardRenderer.density(g), hs = Math.max(1, Math.round(hd * .6f)) / hd;
        for (int i = 0; i < headN; i++) {
            Component label = headR[i] < 0 ? Component.translatable("book.habits.pouch_charms") : Rarity.values()[headR[i]].displayName();
            int color = headR[i] < 0 ? 0xFFE8D2A6 : 0xFF000000 | Rarity.values()[headR[i]].color;
            g.pose().pushPose();
            g.pose().translate(6, headY[i] + 1, 0);
            float fit = Math.min(hs, (POCKET_W - 12f) / Math.max(1, font.width(label))); // "Legendary" is wider than the pouch
            g.pose().scale(fit, fit, 1);
            g.drawString(font, label, 0, 0, color, true);
            g.pose().popPose();
        }
        for (int i = 0; i < pouchN; i++) {
            int sx = (int) pouchX(i), sy = (int) pouchY(i);
            CardRenderer.blit(g, INV_SLOT, sx, sy, 18, 18);
            if (pouch[i] == dragSlot) continue;
            ItemStack st = pouchStack(pouch[i]);
            long age = Util.getMillis() - sigilPopAt;
            if (pouch[i] == sigilPopCode && age < RING_MS) { // the stack a combine just made pops in
                float sc = popScale(age), a = 1 - age / (float) RING_MS;
                g.pose().pushPose();
                g.pose().translate(sx + 9, sy + 9, 0);
                g.pose().scale(sc, sc, 1);
                g.pose().translate(-8, -8, 0);
                g.renderItem(st, 0, 0);
                g.pose().popPose();
                float r = 9 + 6 * (1 - a);
                g.renderOutline(Math.round(sx + 9 - r), Math.round(sy + 9 - r), Math.round(2 * r), Math.round(2 * r), CardRenderer.mul(0xFFFFE27A, a));
            } else g.renderItem(st, sx + 1, sy + 1);
            g.renderItemDecorations(font, st, sx + 1, sy + 1);
            if (pouch[i] == sigilPopCode && age < NEW_MS) g.renderOutline(sx, sy, 18, 18, CardRenderer.mul(0xFFE8A23A, 1 - age / (float) NEW_MS));
            if (dragSlot < 0 && in(mx, my, sx, sy, 18, 18)) {
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0x60FFFFFF);
                tip = new ArrayList<>(getTooltipFromItem(minecraft, st));
                tip.add(Component.translatable(charms && selLine >= 0 ? "book.habits.pouch_reroll" : "book.habits.pouch_sigil")
                        .withStyle(ChatFormatting.GRAY));
                SigilData sd = ProgressionApi.sigilOf(st);
                Rarity up = sd == null ? null : com.habits.progression.Actions.next(sd.rarity());
                if (up == null) tip.add(Component.translatable("book.habits.combine_top").withStyle(ChatFormatting.DARK_GRAY));
                else tip.add(Component.translatable("book.habits.combine_shift", ProgressionApi.sigilCombineCount(), up.displayName())
                        .withStyle(ProgressionApi.sigilCount(minecraft.player, sd) >= ProgressionApi.sigilCombineCount() ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
            }
        }
        if (pouchN == 0) {
            float d = CardRenderer.density(g), fs = Math.max(1, Math.round(d * .6f)) / d;
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("book.habits.pouch_empty"), (int) ((POCKET_W - 16) / fs));
            g.pose().pushPose();
            g.pose().translate(POCKET_W / 2f, 22, 0);
            g.pose().scale(fs, fs, 1);
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), -font.width(lines.get(i)) / 2, i * 10, 0xFFB89A70, false);
            g.pose().popPose();
        }
        return tip;
    }

    /** A refusal the player should see (the server's own message sits under the book): a dark pill on the bottom edge. */
    void refuse(Component why) {
        notice = why;
        noticeAt = Util.getMillis();
        noticeGood = false;
    }

    /** Good news in the same spot, gold instead of red. */
    void inform(Component what) {
        refuse(what);
        noticeGood = true;
    }

    private void drawNotice(GuiGraphics g) {
        if (notice == null) return;
        long age = Util.getMillis() - noticeAt;
        if (age > NOTICE_MS) { notice = null; return; }
        float a = Math.min(1, (NOTICE_MS - age) / 400f);
        int w = font.width(notice) + 12, x = BOOK_X + (BOOK_W - w) / 2, y = BOOK_H - 13; // on the leather, under the page numbers
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        g.fill(x, y, x + w, y + 13, CardRenderer.mul(0xF0200C0C, a));
        g.renderOutline(x, y, w, 13, CardRenderer.mul(noticeGood ? 0xFFE8A23A : 0xFFD9453B, a));
        g.drawString(font, notice, x + 6, y + 3, (Math.max(4, Math.round(255 * a)) << 24) | (noticeGood ? 0xFFD58A : 0xFF8A80), false);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ charms tab

    private static int charmSlotX(int i) {
        return LEFT_PAGE_X + (PAGE_W - (5 * CHARM_SLOT + 4 * CHARM_GAP)) / 2 + i * (CHARM_SLOT + CHARM_GAP);
    }

    private static final int CHARM_SLOT_Y = PAGE_Y + 30, ROWS_Y = PAGE_Y + 26, NAME_H = 12, LINE_H = 11, CHARM_SPACE = 4;
    private final double[] sumPerks = new double[Category.values().length], sumDrops = new double[Category.values().length],
            sumStat = new double[Category.values().length];
    private final CharmData[] statCharm = new CharmData[Category.values().length];

    private static int ownedSkills(HabitsCollection col) {
        int n = 0;
        for (SkillProgress p : col.skills().values()) if (p.level() > 0) n++;
        return n;
    }

    /** Left page: the slot row, the next slot and the totals. Right page: every charm (equipped, then the bag): drag one
     * into a slot, pick a line to reroll, or switch to Combine and pick two. */
    @Nullable
    private List<Component> charmsPages(GuiGraphics g, HabitsCollection col, float mx, float my) {
        List<CharmData> eq = ProgressionApi.equippedCharms(minecraft.player);
        int unlocked = eq.size(), filled = 0;
        for (CharmData c : eq) if (c != null) filled++;
        if (selEquipped && (selCharm >= unlocked || selCharm >= 0 && eq.get(selCharm) == null)) selCharm = selLine = -1;
        CharmData dragCharm = dragSlot < 0 ? null : ProgressionApi.charmOf(dragStack);
        float pulse = .6f + .4f * (float) Math.sin(Util.getMillis() / 140.0);
        List<Component> tip = null;

        // left page
        int x0 = LEFT_PAGE_X + HEAD_DX, x1 = LEFT_PAGE_X + PAGE_W - HEAD_DX, hy = PAGE_Y + 7;
        g.drawString(font, Component.translatable("book.habits.charms"), x0, hy, INK, false);
        String count = filled + "/" + unlocked;
        g.drawString(font, count, x1 - font.width(count), hy, INK_SOFT, false);
        g.fill(x0, hy + 10, x1, hy + 11, RULE);
        for (int i = 0; i < 5; i++) {
            int x = charmSlotX(i), y = CHARM_SLOT_Y;
            boolean locked = i >= unlocked, hover = in(mx, my, x, y, CHARM_SLOT, CHARM_SLOT);
            CharmData c = locked ? null : eq.get(i);
            g.fill(x, y, x + CHARM_SLOT, y + CHARM_SLOT, 0xFF6E5A40);
            g.fill(x + 1, y + 1, x + CHARM_SLOT - 1, y + CHARM_SLOT - 1, locked ? RULE : c != null ? 0xFF2B2420 : 0xFFE3D4B0);
            g.fill(x + 1, y + 1, x + CHARM_SLOT - 1, y + 2, 0x30000000); // inner shadow: the slot sits in the page
            if (c != null) {
                g.renderOutline(x + 1, y + 1, CHARM_SLOT - 2, CHARM_SLOT - 2, 0xFF000000 | c.rarity().color);
                if (i == selCharm) g.renderOutline(x - 2, y - 2, CHARM_SLOT + 4, CHARM_SLOT + 4, 0xFFE8A23A);
                g.pose().pushPose();
                g.pose().translate(x + 4, y + 4, 0);
                g.pose().scale(1.5f, 1.5f, 1);
                g.renderItem(charmStack(c), 0, 0);
                g.pose().popPose();
            } else if (locked) {
                int lx = x + 10, ly = y + 8, lc = INK_SOFT; // padlock at 2x
                g.fill(lx + 4, ly, lx + 8, ly + 2, lc);
                g.fill(lx + 2, ly + 2, lx + 4, ly + 6, lc);
                g.fill(lx + 8, ly + 2, lx + 10, ly + 6, lc);
                g.fill(lx, ly + 6, lx + 12, ly + 15, lc);
            }
            if (dragCharm != null && !locked) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                int rim = CardRenderer.mul(0xFFFFE27A, pulse); // 2 px, so it reads over a Legendary rim
                g.renderOutline(x - 1, y - 1, CHARM_SLOT + 2, CHARM_SLOT + 2, rim);
                g.renderOutline(x - 2, y - 2, CHARM_SLOT + 4, CHARM_SLOT + 4, rim);
                if (hover) g.fill(x + 1, y + 1, x + CHARM_SLOT - 1, y + CHARM_SLOT - 1, 0x50FFFFFF);
                g.pose().popPose();
            }
            if (hover && dragSlot < 0) {
                if (locked) tip = List.of(Component.translatable("book.habits.charm_slot_locked"),
                        Component.translatable("progression.habits.msg.charm_slot_locked").withStyle(ChatFormatting.GRAY));
                else if (c == null) tip = List.of(Component.translatable("book.habits.charm_slot_empty"),
                        Component.translatable("book.habits.charm_slot_drag").withStyle(ChatFormatting.GRAY));
                else {
                    tip = new ArrayList<>(getTooltipFromItem(minecraft, charmStack(c)));
                    tip.removeLast(); // the item's "equip it in the Charms tab" hint
                    tip.add(Component.translatable("book.habits.charm_take").withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
        int ty = CHARM_SLOT_Y + CHARM_SLOT + 8;
        int owned = ownedSkills(col), next = ProgressionApi.nextCharmSlotAt(owned);
        Component nextText = next < 0 ? Component.translatable("book.habits.charm_all") : Component.translatable("book.habits.charm_next", owned, next);
        g.drawString(font, nextText, LEFT_PAGE_X + (PAGE_W - font.width(nextText)) / 2, ty, INK_SOFT, false);
        ty += 16;
        g.drawString(font, Component.translatable("book.habits.charm_totals"), x0, ty, INK, false);
        g.fill(x0, ty + 10, x1, ty + 11, RULE);
        ty += 16;
        java.util.Arrays.fill(sumPerks, 0);
        java.util.Arrays.fill(sumDrops, 0);
        java.util.Arrays.fill(sumStat, 0);
        for (CharmData c : eq) {
            if (c == null) continue;
            int k = c.category().ordinal();
            sumPerks[k] += c.sum(CharmData.LineKind.PERKS);
            sumDrops[k] += c.sum(CharmData.LineKind.DROPS);
            sumStat[k] += c.sum(CharmData.LineKind.STAT);
            statCharm[k] = c;
        }
        if (filled == 0) {
            for (var line : font.split(Component.translatable("book.habits.charm_none"), x1 - x0)) {
                g.drawString(font, line, x0, ty, INK_SOFT, false);
                ty += 10;
            }
        }
        for (Category cat : Category.values()) {
            int k = cat.ordinal();
            for (int kind = 0; kind < 3; kind++) {
                double v = kind == 0 ? sumPerks[k] : kind == 1 ? sumDrops[k] : sumStat[k];
                if (v <= 0) continue;
                g.fill(x0, ty + 2, x0 + 5, ty + 7, 0xFF000000 | cat.tabColor);
                g.drawString(font, statCharm[k].lineText(new CharmData.Line(CharmData.LineKind.values()[kind], v)), x0 + 9, ty, INK, false);
                ty += 11;
            }
        }

        // right page: every charm, equipped ones first, then the book's bag (newest first); scrolls
        return charmList(g, eq, mx, my, pulse, tip);
    }

    /** Right page list: equipped charms (by slot), then the bag. Rebuilt each frame into reused arrays. */
    private final List<CharmData> entries = new ArrayList<>();
    private final int[] eqSlot = new int[5];
    private int eqCount;
    /** Bag index of each bag entry as shown: sorted rarity (best first), then category, then newest, so combinable
     * pairs sit together. The server keeps its own order; clicks map back through this. */
    private int[] bagAt = new int[0];
    private int charmScroll;
    /** A charm just arrived: scroll the list to it once (sorting can put it anywhere). */
    private boolean scrollToNew;
    /** Salvage mode (the header button): a click arms a stored charm, a second click on it breaks it into a sigil. The
     * armed charm is the charm itself (indices shift when a new charm lands at the front). */
    boolean salvageMode;
    @Nullable CharmData armed;
    /** The bag last frame, to spot a charm that just arrived (combine result, drop) and pop it in. */
    @Nullable private List<CharmData> lastBag;
    private long popAt = -10_000;
    /** Reroll target when picked on a stored charm (followed across index shifts). */
    @Nullable private CharmData selData;
    boolean selEquipped = true;
    private static final int POP_MS = 700, RING_MS = 900, NEW_MS = 4000, BAR_H = 32;
    private static final Map<Category, ItemStack> CAT_STACKS = new EnumMap<>(Category.class);

    private static int entryH(CharmData c) {
        return NAME_H + c.lines().size() * LINE_H + CHARM_SPACE;
    }

    private void readEntries(List<CharmData> eq) {
        entries.clear();
        eqCount = 0;
        for (int i = 0; i < eq.size(); i++) if (eq.get(i) != null) { eqSlot[eqCount++] = i; entries.add(eq.get(i)); }
        List<CharmData> bag = ProgressionApi.storedCharms(minecraft.player);
        if (bagAt.length != bag.size()) bagAt = new int[bag.size()];
        Integer[] order = new Integer[bag.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.<Integer, Rarity>comparing(i -> bag.get(i).rarity()).reversed()
                .thenComparing(i -> bag.get(i).category()).thenComparingInt(i -> i));
        for (int i = 0; i < order.length; i++) { bagAt[i] = order[i]; entries.add(bag.get(order[i])); }
    }

    private static int indexOf(List<CharmData> l, @Nullable CharmData c) {
        for (int i = 0; i < l.size(); i++) if (l.get(i) == c) return i;
        return -1;
    }

    private int listBottom() {
        return PAGE_Y + PAGE_H - (salvageMode ? BAR_H + 6 : selLine >= 0 ? 40 : 8);
    }

    private static final int LIST_Y = PAGE_Y + 24;

    /** Keeps picks and the reroll target on the same charms when the bag changes; spots a new arrival. */
    private void followBag(List<CharmData> bag) {
        if (bag == lastBag) return;
        if (lastBag != null && !bag.isEmpty() && indexOf(lastBag, bag.get(0)) < 0 && bag.size() != lastBag.size()) { // same size = a reroll in place
            popAt = Util.getMillis();
            scrollToNew = true;
        }
        if (indexOf(bag, armed) < 0) armed = null;
        if (!selEquipped && selCharm >= 0) {
            int at = indexOf(bag, selData);
            if (at >= 0) selCharm = at; // moved (a new charm in front)
            else if (selCharm < bag.size() && lastBag != null && selCharm < lastBag.size() && lastBag.get(selCharm) == selData) selData = bag.get(selCharm);
            else if (selCharm < bag.size()) selData = bag.get(selCharm); // rerolled in place: a new instance, same index
            else selCharm = selLine = -1;
        }
        lastBag = bag;
    }

    @Nullable
    private List<Component> charmList(GuiGraphics g, List<CharmData> eq, float mx, float my, float pulse, @Nullable List<Component> tip) {
        List<CharmData> bag = ProgressionApi.storedCharms(minecraft.player);
        followBag(bag);
        readEntries(eq);
        int rx0 = RIGHT_PAGE_X + HEAD_DX, rx1 = RIGHT_PAGE_X + PAGE_W - HEAD_DX, hy = PAGE_Y + 7;
        long now = Util.getMillis();
        g.drawString(font, Component.translatable("book.habits.charm_list"), rx0, hy, INK, false);
        // header button: Salvage (enters picking) / Done
        int bw = 48, bx = rx1 - bw, by = hy - 3;
        boolean bHover = in(mx, my, bx, by, bw, 13) && dragSlot < 0;
        button(g, bx, by, bw, 13, Component.translatable(salvageMode ? "book.habits.salvage_done" : "book.habits.salvage"), salvageMode, bHover, true, 0);
        if (bHover) tip = List.of(Component.translatable(salvageMode ? "book.habits.salvage_done_tip" : "book.habits.salvage_tip"));
        if (!salvageMode) {
            Component n = Component.translatable("book.habits.charm_count", bag.size());
            g.drawString(font, n, bx - 6 - font.width(n), hy, INK_SOFT, false);
        }
        g.fill(rx0, hy + 10, rx1, hy + 11, RULE);

        int bottom = listBottom(), content = 0;
        for (CharmData c : entries) content += entryH(c);
        if (scrollToNew) { // the new charm's row a little below the top of the list
            scrollToNew = false;
            int at = 0;
            for (int i = 0; i < entries.size() && !(i >= eqCount && bagAt[i - eqCount] == 0); i++) at += entryH(entries.get(i));
            charmScroll = Math.max(0, at - NAME_H);
        }
        charmScroll = Math.max(0, Math.min(charmScroll, content - (bottom - LIST_Y)));
        g.enableScissor(Math.round(ox + RIGHT_PAGE_X * s), Math.round(oy + (LIST_Y - 3) * s),
                Math.round(ox + (RIGHT_PAGE_X + PAGE_W) * s), Math.round(oy + bottom * s));
        int y = LIST_Y - charmScroll;
        boolean inList = my >= LIST_Y - 3 && my < bottom;
        for (int i = 0; i < entries.size(); i++) {
            CharmData c = entries.get(i);
            int h = entryH(c);
            boolean equipped = i < eqCount;
            int bagIdx = equipped ? -1 : bagAt[i - eqCount];
            if (y + h < LIST_Y - 3 || y > bottom) { y += h; continue; }
            boolean picked = !equipped && c == armed;
            boolean dim = salvageMode && equipped;
            boolean rowHover = inList && dragSlot < 0 && in(mx, my, rx0, y - 2, rx1 - rx0, h - CHARM_SPACE + 1);
            boolean fresh = !equipped && bagIdx == 0 && now - popAt < NEW_MS;
            if (fresh) { // the newest charm: a soft gold glow that fades out
                float a = 1 - Math.max(0, (now - popAt - 1500) / (float) (NEW_MS - 1500));
                g.fill(rx0 - 4, y - 3, rx1 + 2, y + h - CHARM_SPACE, CardRenderer.mul(0x59FFE27A, a));
                g.renderOutline(rx0 - 4, y - 3, rx1 - rx0 + 6, h - CHARM_SPACE + 3, CardRenderer.mul(0xFFE8A23A, a));
            }
            if (picked) {
                g.fill(rx0 - 4, y - 3, rx1 + 2, y + h - CHARM_SPACE, 0x40E8A23A);
                g.renderOutline(rx0 - 4, y - 3, rx1 - rx0 + 6, h - CHARM_SPACE + 3, 0xFFE8A23A);
            } else if (salvageMode && rowHover && !dim) g.fill(rx0 - 4, y - 3, rx1 + 2, y + h - CHARM_SPACE, 0x18000000);
            int ink = dim ? 0x60000000 | (INK & 0xFFFFFF) : INK, lineInk = dim ? 0x606A5640 : 0xFF6A5640;
            // icon (pops in on a new arrival), then the name
            float pop = fresh ? popScale(now - popAt) : 1;
            g.pose().pushPose();
            g.pose().translate(rx0 + 5, y + 3.5f, 0);
            g.pose().scale(.69f * pop, .69f * pop, 1);
            g.pose().translate(-8, -8, 0);
            g.renderItem(charmStack(c), 0, 0);
            g.pose().popPose();
            if (dim) g.fill(rx0 - 1, y - 2, rx0 + 11, y + 10, 0x99EFE7D3); // fade the icon with the text
            if (fresh && now - popAt < RING_MS) {
                float t = (now - popAt) / (float) RING_MS, r = 6 + 10 * t;
                int ring = CardRenderer.mul(0xFFFFE27A, 1 - t);
                g.renderOutline(Math.round(rx0 + 5 - r), Math.round(y + 3.5f - r), Math.round(2 * r), Math.round(2 * r), ring);
            }
            Component name = Component.translatable("item.habits.charm.named", c.rarity().displayName(), c.category().displayName());
            g.drawString(font, name, rx0 + 14, y, ink, false);
            if (fresh) {
                Component nw = Component.translatable("book.habits.new");
                int nx = rx0 + 18 + font.width(name);
                g.fill(nx - 1, y - 1, nx + font.width(nw) + 2, y + 8, 0xFFD9453B);
                g.drawString(font, nw, nx + 1, y, 0xFFFFFFFF, false);
            } else if (equipped) {
                Component on = Component.translatable("book.habits.charm_equipped", eqSlot[i] + 1);
                g.drawString(font, on, rx1 - font.width(on), y, INK_SOFT, false);
            }
            if (rowHover && !salvageMode && my < y + NAME_H - 2) {
                tip = new ArrayList<>(getTooltipFromItem(minecraft, charmStack(c)));
                tip.removeLast(); // the item's pick-up hint
                tip.add(Component.translatable(equipped ? "book.habits.charm_take" : "book.habits.charm_drag").withStyle(ChatFormatting.GRAY));
            }
            if (rowHover && salvageMode) tip = List.of(Component.translatable(equipped ? "book.habits.salvage_equipped"
                    : c == armed ? "book.habits.salvage_confirm" : "book.habits.salvage_arm", c.rarity().displayName()));
            y += NAME_H;
            for (int l = 0; l < c.lines().size(); l++) {
                boolean sel = !salvageMode && selCharm >= 0 && selLine == l && (equipped ? selEquipped && selCharm == eqSlot[i] : !selEquipped && selCharm == bagIdx);
                boolean hover = !salvageMode && inList && in(mx, my, rx0, y - 1, rx1 - rx0, LINE_H);
                if (sel) {
                    g.fill(rx0 + 4, y - 1, rx1, y + LINE_H - 1, 0x40E8A23A);
                    g.renderOutline(rx0 + 4, y - 1, rx1 - rx0 - 4, LINE_H, dragSigil() != null ? CardRenderer.mul(0xFFE8A23A, pulse) : 0xFFE8A23A);
                } else if (hover && dragSlot < 0) g.fill(rx0 + 4, y - 1, rx1, y + LINE_H - 1, 0x18000000);
                g.drawString(font, c.lineText(c.lines().get(l)), rx0 + 9, y + 1, sel ? INK : lineInk, false);
                y += LINE_H;
            }
            y += CHARM_SPACE;
        }
        g.disableScissor();
        if (entries.isEmpty())
            for (var line : font.split(Component.translatable("book.habits.charm_list_empty"), rx1 - rx0))
                g.drawString(font, line, rx0, LIST_Y + 4, INK_SOFT, false);
        if (content > bottom - LIST_Y) { // scroll bar: the list is unlimited
            int track = bottom - LIST_Y, th = Math.max(16, track * track / content), sx = RIGHT_PAGE_X + PAGE_W - 5;
            int ty = LIST_Y + (track - th) * charmScroll / Math.max(1, content - track);
            g.fill(sx, LIST_Y, sx + 3, bottom, 0xFFD9CCAD);
            g.fill(sx, ty, sx + 3, ty + th, INK_SOFT);
        }

        if (salvageMode) salvageBar(g);
        else if (selLine >= 0) { // the cost and how to pay it, at the foot of the page
            CharmData sel = selected();
            if (sel != null) {
                int have = 0; // stored sigils that can pay
                for (SigilData sd : SigilData.ALL) if (sd.rarity().ordinal() >= sel.rarity().ordinal()) have += ProgressionApi.sigilCount(minecraft.player, sd);
                Component cost = Component.translatable("book.habits.reroll_cost", sel.rarity().displayName());
                Component haveLine = Component.translatable("progression.habits.reroll_have", have);
                Component how = Component.translatable("book.habits.reroll_how");
                g.drawString(font, cost, RIGHT_PAGE_X + (PAGE_W - font.width(cost)) / 2, PAGE_Y + PAGE_H - 36, 0xFF8A4A12, false);
                g.drawString(font, haveLine, RIGHT_PAGE_X + (PAGE_W - font.width(haveLine)) / 2, PAGE_Y + PAGE_H - 25, have > 0 ? INK_SOFT : 0xFFB0302A, false);
                g.drawString(font, how, RIGHT_PAGE_X + (PAGE_W - font.width(how)) / 2, PAGE_Y + PAGE_H - 14, INK_SOFT, false);
            }
        }
        return tip;
    }

    /** Overshoot pop: 0.2 -> 1.18 -> 1 over {@link #POP_MS}. */
    private static float popScale(long age) {
        if (age >= POP_MS) return 1;
        float t = age / (float) POP_MS;
        return t < .55f ? .2f + (1.18f - .2f) * (1 - (1 - t / .55f) * (1 - t / .55f)) : 1.18f - .18f * ((t - .55f) / .45f);
    }

    /** A book button like the mock's: brass rim, cream face, a darker lip. */
    private void button(GuiGraphics g, int x, int y, int w, int h, Component label, boolean pressed, boolean hover, boolean enabled, float glow) {
        if (glow > 0) {
            int c = CardRenderer.mul(0xFFFFE27A, glow);
            g.renderOutline(x - 1, y - 1, w + 2, h + 2, c);
            g.renderOutline(x - 2, y - 2, w + 4, h + 4, CardRenderer.mul(0xFFFFB52E, glow * .6f));
        }
        g.fill(x, y, x + w, y + h, 0xFF6E5A40);
        int face = !enabled ? RULE : pressed ? 0xFFE8C77A : hover ? 0xFFFFE9AD : 0xFFF3DFA6;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        if (enabled && !pressed) g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, 0x5A784F14);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + (pressed ? 1 : 0), enabled ? INK : INK_SOFT, false);
    }

    /** Salvage mode's hint at the foot of the page. */
    private void salvageBar(GuiGraphics g) {
        int rx0 = RIGHT_PAGE_X + HEAD_DX, rx1 = RIGHT_PAGE_X + PAGE_W - HEAD_DX, y0 = barY();
        g.fill(rx0 - 4, y0, rx1 + 4, y0 + BAR_H, 0xFFE9DCBC);
        g.renderOutline(rx0 - 4, y0, rx1 - rx0 + 8, BAR_H, 0xFFE8A23A);
        int y = y0 + 6;
        Component hint = armed == null ? Component.translatable("book.habits.salvage_rule")
                : Component.translatable("book.habits.salvage_again", armed.rarity().displayName());
        for (var line : font.split(hint, rx1 - rx0)) {
            g.drawString(font, line, rx0, y, INK, false);
            y += 11;
        }
    }

    private int barY() {
        return PAGE_Y + PAGE_H - BAR_H - 4;
    }

    /** The charm whose line is picked for a reroll (equipped slot or stored), or null. */
    @Nullable
    private CharmData selected() {
        if (selCharm < 0 || selLine < 0) return null;
        if (selEquipped) {
            List<CharmData> eq = ProgressionApi.equippedCharms(minecraft.player);
            return selCharm < eq.size() ? eq.get(selCharm) : null;
        }
        List<CharmData> bag = ProgressionApi.storedCharms(minecraft.player);
        return selCharm < bag.size() ? bag.get(selCharm) : null;
    }

    private static final Map<CharmData, ItemStack> CHARM_STACKS = new java.util.WeakHashMap<>();

    /** A display stack per charm (weak: rerolled charms are new instances). */
    private static ItemStack charmStack(CharmData c) {
        return CHARM_STACKS.computeIfAbsent(c, com.habits.progression.CharmItem::create);
    }

    /** Charm slot under the mouse (locked ones included), or -1. */
    private static int charmSlotAt(float mx, float my) {
        for (int i = 0; i < 5; i++) if (in(mx, my, charmSlotX(i), CHARM_SLOT_Y, CHARM_SLOT, CHARM_SLOT)) return i;
        return -1;
    }

    /** Right page list hit: entry index * 8 + (line + 1), 0 for the name row; -1 = none. Same walk as the drawing. */
    private int charmEntryAt(float mx, float my) {
        if (!in(mx, my, RIGHT_PAGE_X, LIST_Y - 3, PAGE_W, listBottom() - LIST_Y + 3)) return -1;
        int rx0 = RIGHT_PAGE_X + HEAD_DX, rx1 = RIGHT_PAGE_X + PAGE_W - HEAD_DX, y = LIST_Y - charmScroll;
        for (int i = 0; i < entries.size(); i++) {
            CharmData c = entries.get(i);
            if (in(mx, my, rx0 - 4, y - 2, rx1 - rx0 + 4, NAME_H)) return i * 8;
            y += NAME_H;
            for (int l = 0; l < c.lines().size(); l++, y += LINE_H) if (in(mx, my, rx0, y - 1, rx1 - rx0, LINE_H)) return i * 8 + l + 1;
            y += CHARM_SPACE;
        }
        return -1;
    }

    /** Pay for the picked line with a stored sigil (refused here when the one offered is too weak); the server spends the
     * cheapest stored sigil that qualifies. */
    private void reroll(SigilData sigil) {
        CharmData c = selected();
        if (c == null) return;
        if (sigil.rarity().ordinal() < c.rarity().ordinal()) {
            refuse(Component.translatable("progression.habits.msg.reroll_too_weak"));
            return;
        }
        PacketDistributor.sendToServer(new ProgPayloads.RerollCharm(selEquipped, selCharm, selLine));
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
    }

    /** A click on the Charms tab's right page: the header button, the combine bar, or the list. */
    private boolean charmPageClick(float mx, float my) {
        int rx1 = RIGHT_PAGE_X + PAGE_W - HEAD_DX, hy = PAGE_Y + 7;
        if (in(mx, my, rx1 - 48, hy - 3, 48, 13)) {
            salvageMode = !salvageMode;
            armed = null;
            selCharm = selLine = -1;
            click();
            return true;
        }
        int hit = charmEntryAt(mx, my);
        if (hit < 0) return false;
        int i = hit / 8, line = hit % 8 - 1;
        boolean equipped = i < eqCount;
        CharmData c = entries.get(i);
        if (salvageMode) {
            if (equipped) refuse(Component.translatable("book.habits.salvage_equipped"));
            else if (c != armed) { armed = c; click(); }
            else {
                PacketDistributor.sendToServer(new ProgPayloads.SalvageCharm(bagAt[i - eqCount]));
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GRINDSTONE_USE, 1));
                armed = null;
            }
            return true;
        }
        if (line < 0) { // the name row: pick it up to drag into a slot (equipped ones come off with a click on their slot)
            if (!equipped) {
                dragSlot = CHARM_CODE + bagAt[i - eqCount];
                dragFrom = -1;
                dragStack = charmStack(c);
            }
            return true;
        }
        int key = equipped ? eqSlot[i] : bagAt[i - eqCount];
        boolean same = selEquipped == equipped && selCharm == key && selLine == line;
        selEquipped = equipped;
        selCharm = same ? -1 : key;
        selLine = same ? -1 : line;
        selData = same || equipped ? null : c;
        click();
        return true;
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
    }

    /** Drop a dragged sigil on a card: on a socket, or anywhere on the card for its first empty open socket. */
    private void dropSigil(SigilData sigil, float mx, float my) {
        HabitsCollection col = collection.get();
        for (int side = 0; side < 2; side++) {
            int pi = spread * 2 + side;
            if (pi >= shown.size()) continue;
            int px = side == 0 ? LEFT_PAGE_X : RIGHT_PAGE_X;
            List<SkillDef> list = shown.get(pi).skills();
            for (int i = 0; i < list.size(); i++) {
                SkillDef def = list.get(i);
                int cx = slotX(px, i), cy = slotY(i), level = col.get(def.id()).level();
                if (level <= 0 || !in(mx, my, cx - 2, cy - 2, CW + 4, CH + 4)) continue;
                int sock = socketAt(def, cx, cy - 2, mx, my), open = ProgressionApi.socketCount(level); // the hovered card is lifted 2
                if (sock < 0) for (int k = 0; k < open && sock < 0; k++) if (ProgressionApi.sigil(minecraft.player, def.id(), k) == null) sock = k;
                if (!ProgressionApi.fits(sigil.type(), def)) refuse(Component.translatable("progression.habits.msg.wrong_type"));
                else if (sock >= open) refuse(Component.translatable("progression.habits.msg.socket_locked"));
                else if (sock < 0) refuse(Component.translatable("book.habits.sockets_full"));
                else {
                    PacketDistributor.sendToServer(new ProgPayloads.Socket(def.id(), sock, sigil));
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PUT, 1));
                }
                return;
            }
        }
    }

    /** Dev screenshots: start dragging drag code {@code code} (a stored sigil kind, or a stored charm). */
    void debugDrag(int code) {
        dragSlot = code;
        dragStack = code >= CHARM_CODE ? charmStack(ProgressionApi.storedCharms(minecraft.player).get(code - CHARM_CODE))
                : SIGIL_STACKS.computeIfAbsent(SigilData.ALL.get(code - SIGIL_CODE), SigilItem::create);
    }

    /** Dev screenshots: card px center of the name row of list entry {@code i} on the Charms tab's right page. */
    float[] charmEntryCenter(int i) {
        int y = LIST_Y - charmScroll;
        for (int k = 0; k < i && k < entries.size(); k++) y += entryH(entries.get(k));
        return new float[]{RIGHT_PAGE_X + PAGE_W / 2f, y + 3};
    }

    static float[] salvageToggleCenter() {
        return new float[]{RIGHT_PAGE_X + PAGE_W - HEAD_DX - 24, PAGE_Y + 7 + 3};
    }

    /** Dev screenshots: card px center of socket {@code socket} on grid slot {@code i} of the current spread (card lifted, as hovered). */
    float[] socketCenter(int i, int socket) {
        SkillDef def = spreadSkills().get(i);
        float[] c = slotCenter(i);
        int cx = Math.round(c[0] - CW / 2f), cy = Math.round(c[1] - CH / 2f) - 2;
        return new float[]{socketX(cx, socket) + SOCK / 2f, socketY(def, cy) + SOCK / 2f};
    }

    /** Dev screenshots: card px centers of pouch entry {@code i} and charm slot {@code i}. */
    float[] pouchCenter(int i) {
        return new float[]{pouchX(i) + 9, pouchY(i) + 9};
    }

    static float[] charmSlotCenter(int i) {
        return new float[]{charmSlotX(i) + CHARM_SLOT / 2f, CHARM_SLOT_Y + CHARM_SLOT / 2f};
    }

    /** Dev screenshots: pouch code of pouch entry {@code i} (-1 = none). */
    int pouchSlot(int i) {
        refreshPouch();
        return i < pouchN ? pouch[i] : -1;
    }

    private int tabY0() {
        int n = tabs.size() + 1; // + Charms
        return PAGE_Y + (PAGE_H - (n * TAB_STEP - (TAB_STEP - TAB_H))) / 2;
    }

    // ------------------------------------------------------------------ footer (specializations on the right page)

    /** Right page footer: the player's two specializations and how to change them (hover). */
    @Nullable
    private List<Component> specsFooter(GuiGraphics g, float mx, float my) {
        var picks = com.habits.core.Specs.of(minecraft.player).picks();
        if (charms || picks.isEmpty()) return null;
        float d = CardRenderer.density(g), fs = Math.max(1, Math.round(d * .75f)) / d;
        float x = RIGHT_PAGE_X + HEAD_DX, y = PAGE_Y + PAGE_H - 12 + (8 - 8 * fs) / 2, x0 = x;
        var specs = com.habits.core.Specs.of(minecraft.player);
        for (Category c : picks) {
            SkillDef clazz = specs.classOn(c);
            Component name = clazz == null ? c.displayName() : clazz.displayName();
            int color = clazz == null ? c.tabColor : clazz.rarity().color;
            g.pose().pushPose();
            g.pose().translate(x, y - 2, 0);
            g.pose().scale(.6f, .6f, 1);
            g.renderItem(catStack(c), 0, 0);
            g.pose().popPose();
            x += 11;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(fs, fs, 1);
            g.drawString(font, name, 0, 0, 0xFF000000 | color, false);
            g.pose().popPose();
            x += font.width(name) * fs + 6;
        }
        if (!in(mx, my, x0 - 2, y - 3, x - x0 + 2, 12)) return null;
        return List.of(Component.translatable("book.habits.specs"),
                Component.translatable("book.habits.specs_tip").withStyle(ChatFormatting.GRAY));
    }

    private static final int TITLES_TAB = 0x8E6FD8;
    private static final ItemStack TITLES_ICON = new ItemStack(Items.NAME_TAG);

    private static ItemStack catStack(Category c) {
        return CAT_STACKS.computeIfAbsent(c, k -> new ItemStack(k.icon()));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && !charms) {
            SkillDef hit = cardAt((float) (mouseX - ox) / s, (float) (mouseY - oy) / s);
            if (hit != null && !hit.forks().isEmpty()) return cycleFork(hit, hasShiftDown() ? 1 : 0);
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        float mx = (float) (mouseX - ox) / s, my = (float) (mouseY - oy) / s;
        int pe = pouchAt(mx, my);
        if (pe >= 0 && hasShiftDown()) return combineSigils(SigilData.ALL.get(pouch[pe] - SIGIL_CODE));
        if (pe >= 0) { // pick a sigil up; mouseReleased decides what it was dropped on
            dragFrom = pe;
            debugDrag(pouch[pe]);
            return true;
        }
        if (!charms && spread > 0 && in(mx, my, PREV_X - 2, ARROW_Y - 2, 18, 13)) return flip(spread - 1);
        if (!charms && spread < spreads() - 1 && in(mx, my, NEXT_X - 2, ARROW_Y - 2, 18, 13)) return flip(spread + 1);
        int tabX = BOOK_X + BOOK_W - LB - 1, tabY0 = tabY0();
        for (int i = 0; i <= tabs.size(); i++)
            if (in(mx, my, tabX, tabY0 + i * TAB_STEP, LB + 1 + TAB_OUT, TAB_H)) {
                if (searching()) setQuery(""); // a tab leaves the search
                boolean was = charms;
                charms = i == tabs.size();
                if (charms) {
                    if (!was) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1));
                    return true;
                }
                int to = firstPage(tabs.get(i)) / 2;
                if (was && to == spread) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1));
                return flip(to);
            }
        if (charms) {
            int slot = charmSlotAt(mx, my);
            List<CharmData> eq = ProgressionApi.equippedCharms(minecraft.player);
            if (slot >= 0 && slot < eq.size() && eq.get(slot) != null) { // a click takes it off, back to the list
                PacketDistributor.sendToServer(new ProgPayloads.UnequipCharm(slot));
                if (selEquipped && selCharm == slot) selCharm = selLine = -1;
                click();
                return true;
            }
            charmPageClick(mx, my);
            return true;
        }
        if (unsocketAt(mx, my)) return true;
        SkillDef hit = cardAt(mx, my);
        if (hit != null && hit.title().isPresent() && collection.get().get(hit.id()).level() > 0) {
            boolean shown = minecraft.player.getData(com.habits.core.CoreRegistries.SHOWN_TITLE).filter(hit.id()::equals).isPresent();
            PacketDistributor.sendToServer(new Payloads.ShowTitle(shown ? java.util.Optional.empty() : java.util.Optional.of(hit.id())));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            return true;
        }
        if (hit != null && collection.get().get(hit.id()).level() > 0) {
            PacketDistributor.sendToServer(new Payloads.TogglePerk(hit.id()));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Right-click a card: the next option of its {@code which}-th fork step (0 = Lv5, 1 = Lv10 for most), once reached. */
    private boolean cycleFork(SkillDef def, int which) {
        if (which >= def.forks().size()) which = 0;
        SkillDef.ForkStep step = def.forks().get(which);
        if (collection.get().get(def.id()).level() < step.level()) {
            refuse(Component.translatable("book.habits.fork_locked", step.level()));
            return true;
        }
        int at = step.options().indexOf(com.habits.core.Forks.chosen(minecraft.player, def, step)); // -1 while none: the first
        SkillDef.ForkOption next = step.options().get((at + 1) % step.options().size());
        PacketDistributor.sendToServer(new Payloads.SetFork(def.id(), step.level(), next.id()));
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
        return true;
    }

    /** Shift-click on a pouch stack: two into one of the next rarity (checked here, then again on the server). */
    private boolean combineSigils(SigilData kind) {
        Rarity up = com.habits.progression.Actions.next(kind.rarity());
        if (up == null) refuse(Component.translatable("progression.habits.msg.combine_top"));
        else if (ProgressionApi.sigilCount(minecraft.player, kind) < ProgressionApi.sigilCombineCount()) refuse(Component.translatable("progression.habits.msg.combine_need_two"));
        else {
            PacketDistributor.sendToServer(new ProgPayloads.CombineSigils(kind));
            SigilData made = new SigilData(kind.type(), up);
            sigilPopCode = sigilCode(made);
            sigilPopAt = Util.getMillis();
            Component type = Component.translatable("sigil.habits." + kind.type().getSerializedName());
            inform(Component.translatable("book.habits.combine_sigil_done", ProgressionApi.sigilCombineCount(), kind.rarity().displayName(), type, up.displayName(), type));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SMITHING_TABLE_USE, 1));
        }
        return true;
    }

    /** Dev screenshots: the shift-click path without a held Shift key. */
    void combineSigilsForShots(SigilData kind) {
        combineSigils(kind);
    }

    /** A click (or the start of a drag) on a filled socket takes its sigil out. True when the click hit a socket. */
    private boolean unsocketAt(float mx, float my) {
        HabitsCollection col = collection.get();
        for (int side = 0; side < 2; side++) {
            int pi = spread * 2 + side;
            if (pi >= shown.size()) continue;
            int px = side == 0 ? LEFT_PAGE_X : RIGHT_PAGE_X;
            List<SkillDef> list = shown.get(pi).skills();
            for (int i = 0; i < list.size(); i++) {
                SkillDef def = list.get(i);
                int cx = slotX(px, i), cy = slotY(i), level = col.get(def.id()).level();
                if (level <= 0 || !in(mx, my, cx, cy - 2, CW, CH + 2)) continue;
                int sock = socketAt(def, cx, cy - 2, mx, my);
                if (sock < 0) return false;
                if (sock < ProgressionApi.socketCount(level) && ProgressionApi.sigil(minecraft.player, def.id(), sock) != null) {
                    PacketDistributor.sendToServer(new ProgPayloads.Unsocket(def.id(), sock));
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
                }
                return true; // an empty or locked socket swallows the click (no perk toggle)
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || dragSlot < 0) return super.mouseReleased(mouseX, mouseY, button);
        float mx = (float) (mouseX - ox) / s, my = (float) (mouseY - oy) / s;
        drop(mx, my);
        dragSlot = dragFrom = -1;
        dragStack = ItemStack.EMPTY;
        return true;
    }

    /** What a released sigil or charm lands on. A sigil released on its own pouch entry was a click: it pays a reroll. */
    void drop(float mx, float my) {
        SigilData sigil = ProgressionApi.sigilOf(dragStack);
        CharmData charm = ProgressionApi.charmOf(dragStack);
        int pe = pouchAt(mx, my);
        if (pe >= 0 && pe == dragFrom) {
            if (sigil != null && charms && selLine >= 0) reroll(sigil);
            return;
        }
        if (charms) {
            int slot = charmSlotAt(mx, my);
            if (charm != null && slot >= 0) {
                if (slot >= ProgressionApi.charmSlots(minecraft.player)) refuse(Component.translatable("progression.habits.msg.charm_slot_locked"));
                else {
                    PacketDistributor.sendToServer(new ProgPayloads.EquipCharm(slot, dragSlot - CHARM_CODE));
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PUT, 1));
                }
            } else if (sigil != null && selLine >= 0 && in(mx, my, RIGHT_PAGE_X, PAGE_Y, PAGE_W, PAGE_H)) reroll(sigil);
            return;
        }
        if (sigil != null) dropSigil(sigil, mx, my);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (charms) { charmScroll -= (int) Math.round(scrollY * 20); return true; } // clamped when drawn
        if (scrollY != 0) return flip(spread + (scrollY < 0 ? 1 : -1));
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN) return flip(spread + 1);
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP) return flip(spread - 1);
        if (!query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { setQuery(""); return true; } // first Esc clears, the next closes
            if (key == GLFW.GLFW_KEY_BACKSPACE) { setQuery(hasControlDown() ? "" : query.substring(0, query.length() - 1)); return true; }
        } else if (minecraft.options.keyInventory.matches(key, scan)) { onClose(); return true; } // with text, E types
        return super.keyPressed(key, scan, mods);
    }

    /** Typing anywhere in the book searches; no box to click first. */
    @Override
    public boolean charTyped(char c, int mods) {
        if (Character.isISOControl(c) || query.isEmpty() && c == ' ') return false;
        if (query.length() < BookSearch.MAX_LEN) setQuery(query + c);
        return true;
    }

    private boolean flip(int to) {
        if (charms) return true; // the Charms tab is one spread
        to = Math.max(0, Math.min(to, spreads() - 1));
        if (to == spread) return true;
        spread = to;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private SkillDef cardAt(float mx, float my) {
        for (int side = 0; side < 2; side++) {
            int pi = spread * 2 + side;
            if (pi >= shown.size()) continue;
            int px = side == 0 ? LEFT_PAGE_X : RIGHT_PAGE_X;
            List<SkillDef> list = shown.get(pi).skills();
            for (int i = 0; i < list.size(); i++)
                if (in(mx, my, slotX(px, i), slotY(i), CW, CH)) return list.get(i);
        }
        return null;
    }

    private int pageOf(SkillDef def) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).skills().contains(def)) return i;
        return -1;
    }

    private int firstPage(Category c) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).category() == c) return i;
        return 0;
    }

    /** Live count (the collection changes while the book is open). */
    private int owned(Category c) {
        HabitsCollection col = collection.get();
        int n = 0;
        for (Page p : pages)
            if (p.category() == c) for (SkillDef d : p.skills()) if (col.get(d.id()).level() > 0) n++;
        return n;
    }

    private int total(Category c) {
        for (Page p : pages) if (p.category() == c) return p.total();
        return 0;
    }

    private static boolean in(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private static int darken(int rgb) {
        return ((((rgb >> 16) & 0xFF) * 3 / 4) << 16) | ((((rgb >> 8) & 0xFF) * 3 / 4) << 8) | ((rgb & 0xFF) * 3 / 4);
    }

    /** Dev screenshots: the skills on the current spread, in slot order (left page then right page). */
    List<SkillDef> spreadSkills() {
        List<SkillDef> out = new ArrayList<>();
        for (int pi = spread * 2; pi < Math.min(shown.size(), spread * 2 + 2); pi++) out.addAll(shown.get(pi).skills());
        return out;
    }

    /** Dev screenshots: card px -> GUI coordinates (for driving the real mouse handlers). */
    double[] toGui(float x, float y) {
        return new double[]{ox + x * s, oy + y * s};
    }

    private static int slotX(int pageX, int i) {
        return pageX + GRID_DX + (i % PER_PAGE % COLS) * (CW + GAP);
    }

    private static int slotY(int i) {
        return PAGE_Y + GRID_DY + (i % PER_PAGE / COLS) * (CH + GAP);
    }

    /** Dev screenshots: card px center of grid slot {@code i} (0..2 * PER_PAGE - 1) of the current spread. */
    static float[] slotCenter(int i) {
        return new float[]{slotX(i < PER_PAGE ? LEFT_PAGE_X : RIGHT_PAGE_X, i) + CW / 2f, slotY(i) + CH / 2f};
    }
}
