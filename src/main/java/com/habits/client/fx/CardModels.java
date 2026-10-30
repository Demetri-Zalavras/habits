package com.habits.client.fx;

import com.habits.Habits;
import com.habits.core.CardData;
import com.habits.core.CardItems;
import com.habits.core.CoreRegistries;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The card item everywhere (slot, hand, ground, frame): a plain baked model, no custom renderer. Each rarity has its own
 * item/generated card ({@code models/item/fx/card_<rarity>}); the skill's icon quads are baked flat onto the card face
 * once per (skill, rarity) and cached, so a card costs about what one vanilla item costs. A custom renderer measured
 * ~10 us per card per frame.
 */
final class CardModels {
    private static final ModelResourceLocation CARD = ModelResourceLocation.inventory(Habits.id("card"));
    /** The card back (wood, gold trim, diamond emblem): its rear face replaces every card's rear face. */
    private static final ModelResourceLocation BACK = ModelResourceLocation.standalone(Habits.id("item/fx/card_back"));
    private static List<BakedQuad> backFace = List.of();
    /** One baked card per rarity. */
    private static final Map<Rarity, ModelResourceLocation> BY_KIND = new HashMap<>();
    /** Built card models; cleared on every model bake and skill reload. */
    private static final Map<CardData, BakedModel> CACHE = new HashMap<>();

    static {
        for (Rarity r : Rarity.values())
            BY_KIND.put(r, ModelResourceLocation.standalone(Habits.id("item/fx/card_" + r.getSerializedName())));
    }

    private CardModels() {}

    static void register(ModelEvent.RegisterAdditional e) {
        BY_KIND.values().forEach(e::register);
        e.register(BACK);
    }

    static void bake(ModelEvent.ModifyBakingResult e) {
        CACHE.clear();
        Map<ModelResourceLocation, BakedModel> models = e.getModels();
        BakedModel base = models.get(CARD);
        if (base == null) return;
        BakedModel back = models.get(BACK);
        backFace = back == null ? List.of() : rearQuads(back);
        Map<Rarity, BakedModel> kinds = new HashMap<>();
        BY_KIND.forEach((k, id) -> kinds.put(k, models.getOrDefault(id, base)));
        models.put(CARD, new BakedModelWrapper<>(base) {
            private final ItemOverrides overrides = new ItemOverrides() {
                @Override
                public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
                    CardData d = CardItems.dataOf(stack);
                    if (d == null) return base;
                    BakedModel m = CACHE.get(d);
                    if (m == null) {
                        m = build(kinds.get(d.rarity()), d);
                        CACHE.put(d, m);
                    }
                    return m;
                }
            };

            @Override
            public ItemOverrides getOverrides() {
                return overrides;
            }
        });
    }

    static void clear() {
        CACHE.clear();
    }

    /** Icon quads placed on one card face at depth {@code z}; {@code keep} = only quads facing that way (null = all). */
    private static List<BakedQuad> face(List<BakedQuad> src, ItemTransform gui, float z, @Nullable Direction keep) {
        Matrix4f mat = new Matrix4f()
                .translate(.5f, 8.5f / 16, z)
                .scale(.5f)
                .translate(gui.translation.x(), gui.translation.y(), gui.translation.z())
                .rotate(new Quaternionf().rotationXYZ((float) Math.toRadians(gui.rotation.x()),
                        (float) Math.toRadians(gui.rotation.y()), (float) Math.toRadians(gui.rotation.z())))
                .scale(gui.scale.x(), gui.scale.y(), gui.scale.z())
                .translate(-.5f, -.5f, -.5f);
        List<BakedQuad> in = keep == null ? src : src.stream().filter(q -> q.getDirection() == keep).toList();
        return QuadTransformers.applying(new Transformation(mat)).process(in);
    }

    /** The rarity card with the icon's quads flattened onto its front face (z 8.5/16, facing the viewer in a slot). */
    private static BakedModel build(BakedModel card, CardData d) {
        RandomSource rand = RandomSource.create(42);
        // the card with its rear face swapped for the card back design
        List<BakedQuad> quads = new ArrayList<>();
        for (BakedQuad q : card.getQuads(null, null, rand)) if (backFace.isEmpty() || q.getDirection() != Direction.NORTH) quads.add(q);
        for (Direction dir : Direction.values()) quads.addAll(card.getQuads(null, dir, rand));
        quads.addAll(backFace);
        SkillDef def = SkillRegistry.get(d.skill());
        ItemStack icon = def == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(def.icon()));
        BakedModel im = icon.isEmpty() || icon.is(CoreRegistries.CARD.get()) ? null
                : Minecraft.getInstance().getItemRenderer().getModel(icon, null, null, 0);
        if (im == null || im.isCustomRenderer()) return wrap(card, quads, null); // no icon, or a BEWLR icon with no quads
        List<BakedQuad> src = new ArrayList<>(im.getQuads(null, null, rand));
        for (Direction dir : Direction.values()) src.addAll(im.getQuads(null, dir, rand));
        ItemTransform gui = im.getTransforms().getTransform(ItemDisplayContext.GUI);
        // in a slot: the icon as it looks in a slot (its GUI transform), half size, just in front of the card face. A
        // block icon is a cube (~0.3 deep here): float it wholly in front so it never pokes through the back. Uniform
        // scale only: a squashed z left the packed normals unnormalised and the icon rendered black.
        List<BakedQuad> slot = new ArrayList<>(quads);
        slot.addAll(face(src, gui, 8.5f / 16 + (im.isGui3d() ? .17f : .02f), im.isGui3d() ? null : Direction.SOUTH));
        // in the world (ground, frame, hand): the same picture printed flat on the face, nothing past the card's
        // thickness (a 3D icon sticking out looked bad, user 2026-10-08)
        List<BakedQuad> world = new ArrayList<>(quads);
        world.addAll(printed(src, gui));
        return wrap(card, slot, wrap(card, world, null));
    }

    /** The icon's viewer-facing quads (as seen in a slot) flattened onto the card face: depth squashed to ~0 (keeping
     * the faces' draw order), normals reset to face front so the lighting stays even. */
    private static List<BakedQuad> printed(List<BakedQuad> src, ItemTransform gui) {
        Quaternionf rot = new Quaternionf().rotationXYZ((float) Math.toRadians(gui.rotation.x()),
                (float) Math.toRadians(gui.rotation.y()), (float) Math.toRadians(gui.rotation.z()));
        Matrix4f mat = new Matrix4f()
                .translate(.5f, 8.5f / 16, 8.5f / 16 + .002f)
                .scale(.5f, .5f, .002f)
                .translate(gui.translation.x(), gui.translation.y(), gui.translation.z())
                .rotate(rot)
                .scale(gui.scale.x(), gui.scale.y(), gui.scale.z())
                .translate(-.5f, -.5f, -.5f);
        List<BakedQuad> facing = new ArrayList<>();
        org.joml.Vector3f n = new org.joml.Vector3f();
        for (BakedQuad q : src) {
            n.set(q.getDirection().getStepX(), q.getDirection().getStepY(), q.getDirection().getStepZ()).rotate(rot);
            if (n.z > .01f) facing.add(q);
        }
        List<BakedQuad> out = QuadTransformers.applying(new Transformation(mat)).process(facing);
        for (BakedQuad q : out) {
            int[] v = q.getVertices();
            for (int i = 0; i < 4; i++) v[i * IQuadTransformer.STRIDE + IQuadTransformer.NORMAL] = 127 << 16; // (0, 0, 1)
        }
        return out;
    }

    /** The rear face quads of a generated item model (the full-size layer, facing north). */
    private static List<BakedQuad> rearQuads(BakedModel m) {
        RandomSource rand = RandomSource.create(42);
        List<BakedQuad> out = new ArrayList<>();
        for (BakedQuad q : m.getQuads(null, null, rand)) if (q.getDirection() == Direction.NORTH) out.add(q);
        out.addAll(m.getQuads(null, Direction.NORTH, rand));
        return out;
    }

    /** A baked card from {@code quads}; outside a GUI slot it hands over to {@code world} when given. */
    private static BakedModel wrap(BakedModel card, List<BakedQuad> quads, @Nullable BakedModel world) {
        return new BakedModelWrapper<>(card) {
            @Override
            public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource r) {
                return side == null ? quads : List.of();
            }

            @Override
            public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource r,
                                            net.neoforged.neoforge.client.model.data.ModelData data,
                                            @Nullable net.minecraft.client.renderer.RenderType type) {
                return getQuads(state, side, r);
            }

            @Override
            public ItemOverrides getOverrides() {
                return ItemOverrides.EMPTY;
            }

            // the wrapper would hand back the plain card from these two and skip the icon quads
            @Override
            public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
                return self;
            }

            private final List<BakedModel> self = List.of(this); // per card drawn per frame: no new list each call

            @Override
            public BakedModel applyTransform(ItemDisplayContext ctx, com.mojang.blaze3d.vertex.PoseStack ps, boolean leftHand) {
                originalModel.applyTransform(ctx, ps, leftHand);
                return world != null && ctx != ItemDisplayContext.GUI ? world : this;
            }
        };
    }
}
