package io.lakio.farfield.client;

import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.lod.RenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Farfield's settings screen, opened from the mod list (NeoForge's Mods
 * screen, Mod Menu on Fabric). Changes apply at once and are saved to
 * {@code config/farfield-client.properties} when the screen closes; the
 * {@code /farfield} commands change the same values.
 *
 * <p>Tabs, as the game's world creation screen: Rendering (what every
 * player sets), Preview, Performance (thread counts) and Debug. Each tab
 * is an options list of its own; the tab last open comes back while the
 * game runs.
 */
public final class FarfieldSettingsScreen extends OptionsSubScreen {

    /** The tab open when the screen last closed, this session. */
    private static int lastTab;

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<OptionsList> lists = new ArrayList<>();
    private TabNavigationBar tabNavigationBar;

    /** Texture distance slider: 0 off, 1 to {@link #TEXTURE_STEPS} times this many blocks, then all LODs. */
    private static final int TEXTURE_STEP = 64;
    private static final int TEXTURE_STEPS = RenderSettings.MAX_TEXTURE_DISTANCE / TEXTURE_STEP;
    private static final int TEXTURE_ALL = TEXTURE_STEPS + 1;
    private static final int FOG_STEP = 128;
    /** Last step of the normal fog slider (off); the thick fog slider has one more (vanilla first). */
    private static final int FOG_STEPS =
            (RenderConfig.MAX_FOG_DISTANCE - RenderConfig.MIN_FOG_DISTANCE) / FOG_STEP + 1;

    public FarfieldSettingsScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable("farfield.options.title"));
    }

    /** The screen on a given tab (0 first; past the last, the last), for the agent harness. */
    public static FarfieldSettingsScreen onTab(Screen lastScreen, int tab) {
        lastTab = tab;
        return new FarfieldSettingsScreen(lastScreen);
    }

    @Override
    protected void init() {
        lists.clear();
        OptionsList rendering = newList();
        addRendering(rendering);
        OptionsList preview = newList();
        addPreview(preview);
        OptionsList performance = newList();
        addPerformance(performance);
        OptionsList debug = newList();
        addDebug(debug);
        tabNavigationBar = TabNavigationBar.builder(tabManager, width)
                .addTabs(new ListTab("farfield.options.tab.rendering", rendering),
                        new ListTab("farfield.options.tab.preview", preview),
                        new ListTab("farfield.options.tab.performance", performance),
                        new ListTab("farfield.options.tab.debug", debug))
                .build();
        addRenderableWidget(tabNavigationBar);
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        layout.visitWidgets(w -> {
            w.setTabOrderGroup(1);
            addRenderableWidget(w);
        });
        tabNavigationBar.selectTab(Math.min(lastTab, lists.size() - 1), false);
        repositionElements();
    }

    private OptionsList newList() {
        OptionsList l = new OptionsList(minecraft, width, this);
        lists.add(l);
        return l;
    }

    /** Unused: the options go into the tabs' lists ({@link #init}). */
    @Override
    protected void addOptions() {
    }

    @Override
    protected void repositionElements() {
        if (tabNavigationBar == null) {
            return;
        }
        tabNavigationBar.setWidth(width);
        tabNavigationBar.arrangeElements();
        int top = tabNavigationBar.getRectangle().bottom();
        layout.setHeaderHeight(top);
        layout.arrangeElements();
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - layout.getFooterHeight() - top));
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return tabNavigationBar.keyPressed(key) || super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        graphics.blit(CreateWorldScreen.TAB_HEADER_BACKGROUND, 0, 0, 0.0F, 0.0F, width, layout.getHeaderHeight(), 16, 16);
        renderMenuBackground(graphics, 0, layout.getHeaderHeight(), width, height);
    }

    @Override
    public void onClose() {
        for (OptionsList l : lists) {
            l.applyUnsavedChanges();
        }
        Tab current = tabManager.getCurrentTab();
        for (int i = 0; i < lists.size(); i++) {
            if (current instanceof ListTab t && t.list == lists.get(i)) {
                lastTab = i;
            }
        }
        minecraft.setScreen(lastScreen);
    }

    /** A tab showing one options list over the whole tab area. */
    private static final class ListTab implements Tab {
        private final Component title;
        private final OptionsList list;

        ListTab(String key, OptionsList list) {
            this.title = Component.translatable(key);
            this.list = list;
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(list);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            list.updateSizeAndPosition(area.width(), area.height(), area.top());
        }
    }

    /** What every player sets: the LODs on or off, their detail, textures and fog. */
    private static void addRendering(OptionsList list) {
        list.addBig(OptionInstance.createBoolean("farfield.options.rendering",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.rendering.tooltip")),
                RenderSettings.enabled(), RenderSettings::set));
        // Applied when the slider is released: each new value re-selects the LOD sections.
        list.addBig(new OptionInstance<>("farfield.options.detail",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.detail.tooltip")),
                (caption, pixels) -> Options.genericValueLabel(caption,
                        Component.translatable("farfield.options.detail.value", pixels)),
                new OptionInstance.IntRange((int) RenderSettings.MIN_VOXEL_PIXELS, (int) RenderSettings.MAX_VOXEL_PIXELS, false),
                Math.round(RenderSettings.voxelPixels()),
                pixels -> RenderSettings.setVoxelPixels(pixels)));
        list.addBig(new OptionInstance<>("farfield.options.textures",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.textures.tooltip")),
                (caption, step) -> Options.genericValueLabel(caption, textureLabel(step)),
                new OptionInstance.IntRange(0, TEXTURE_ALL),
                textureStep(RenderSettings.textureDistance()),
                step -> RenderSettings.setTextureDistance(textureDistance(step))));
        list.addBig(new OptionInstance<>("farfield.options.fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.fog.tooltip")),
                (caption, step) -> Options.genericValueLabel(caption, fogLabel(fogDistance(step, false))),
                new OptionInstance.IntRange(0, FOG_STEPS),
                fogStep(RenderSettings.fogDistance(), false),
                step -> RenderSettings.setFogDistance(fogDistance(step, false))));
        list.addBig(new OptionInstance<>("farfield.options.thick_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.thick_fog.tooltip")),
                (caption, step) -> Options.genericValueLabel(caption, fogLabel(fogDistance(step, true))),
                new OptionInstance.IntRange(0, FOG_STEPS + 1),
                fogStep(RenderSettings.thickFogDistance(), true),
                step -> RenderSettings.setThickFogDistance(fogDistance(step, true))));
    }

    /** The noise preview beyond the stored terrain. */
    private static void addPreview(OptionsList list) {
        list.addBig(OptionInstance.createBoolean("farfield.options.noise_preview",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.noise_preview.tooltip")),
                RenderSettings.noisePreview(), RenderSettings::setNoisePreview));
        list.addBig(OptionInstance.createBoolean("farfield.options.preview_light",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.preview_light.tooltip")),
                RenderSettings.previewLight(), RenderSettings::setPreviewLight));
        list.addBig(previewFeature("farfield.options.preview_trees", RenderConfig.FEATURE_TREES));
        list.addBig(previewFeature("farfield.options.preview_plants", RenderConfig.FEATURE_PLANTS));
        list.addBig(previewFeature("farfield.options.preview_others", RenderConfig.FEATURE_OTHERS));
    }

    /** Shown or hidden: one kind of the preview's features (plan item D.3). */
    private static OptionInstance<Boolean> previewFeature(String key, int feature) {
        return OptionInstance.createBoolean(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, shown) -> Component.translatable(
                        shown ? "farfield.options.preview_feature.shown" : "farfield.options.preview_feature.hidden"),
                RenderSettings.previewShows(feature), shown -> RenderSettings.setPreviewShows(feature, shown));
    }

    private static void addPerformance(OptionsList list) {
        list.addBig(threadSlider("farfield.options.mesh_threads", RenderSettings.meshThreads(),
                RenderSettings::setMeshThreads));
        list.addBig(threadSlider("farfield.options.preview_threads", RenderSettings.previewThreads(),
                RenderSettings::setPreviewThreads));
    }

    private static void addDebug(OptionsList list) {
        list.addBig(OptionInstance.createBoolean("farfield.options.level_colors",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.level_colors.tooltip")),
                RenderSettings.debugLevels(), RenderSettings::setDebugLevels));
        list.addBig(OptionInstance.createBoolean("farfield.options.performance_logs",
                OptionInstance.cachedConstantTooltip(Component.translatable("farfield.options.performance_logs.tooltip")),
                RenderSettings.performanceLogs(), RenderSettings::setPerformanceLogs));
    }

    @Override
    public void removed() {
        super.removed();
        RenderSettings.save();
    }

    /** A thread count slider: Auto first (the count it stands for on this machine shown), then 1 to 8. */
    private static OptionInstance<Integer> threadSlider(String key, int value,
                                                        java.util.function.IntConsumer setter) {
        return new OptionInstance<>(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, threads) -> Options.genericValueLabel(caption, threads == RenderConfig.AUTO
                        ? Component.translatable("farfield.options.threads.auto", RenderSettings.effectiveThreads(threads))
                        : Component.literal(Integer.toString(threads))),
                new OptionInstance.IntRange(RenderConfig.AUTO, RenderConfig.MAX_THREADS),
                value,
                setter::accept);
    }

    private static Component textureLabel(int step) {
        if (step == 0) {
            return Component.translatable("farfield.options.textures.off");
        }
        if (step == TEXTURE_ALL) {
            return Component.translatable("farfield.options.textures.all");
        }
        return Component.translatable("farfield.options.textures.blocks", step * TEXTURE_STEP);
    }

    /**
     * Fog distance sliders: {@link RenderConfig#MIN_FOG_DISTANCE} to
     * {@link RenderConfig#MAX_FOG_DISTANCE} by {@link #FOG_STEP}, then off;
     * the thick fog slider starts with vanilla.
     */
    private static int fogDistance(int step, boolean thick) {
        if (thick) {
            if (step == 0) {
                return RenderConfig.FOG_VANILLA;
            }
            step--;
        }
        int blocks = RenderConfig.MIN_FOG_DISTANCE + step * FOG_STEP;
        return blocks > RenderConfig.MAX_FOG_DISTANCE ? RenderConfig.FOG_OFF : blocks;
    }

    private static int fogStep(int blocks, boolean thick) {
        int offset = thick ? 1 : 0;
        if (blocks == RenderConfig.FOG_VANILLA) {
            return 0;
        }
        if (blocks == RenderConfig.FOG_OFF) {
            return FOG_STEPS + offset;
        }
        return Math.round((blocks - RenderConfig.MIN_FOG_DISTANCE) / (float) FOG_STEP) + offset;
    }

    private static Component fogLabel(int blocks) {
        if (blocks == RenderConfig.FOG_OFF) {
            return Component.translatable("farfield.options.fog.off");
        }
        if (blocks == RenderConfig.FOG_VANILLA) {
            return Component.translatable("farfield.options.fog.vanilla");
        }
        return Component.translatable("farfield.options.fog.blocks", blocks);
    }

    private static int textureDistance(int step) {
        return step == TEXTURE_ALL ? RenderSettings.ALL_DISTANCES : step * TEXTURE_STEP;
    }

    /** The nearest slider step; a distance set by command keeps its exact value until the slider moves. */
    private static int textureStep(int blocks) {
        return blocks == RenderSettings.ALL_DISTANCES ? TEXTURE_ALL : Math.round(blocks / (float) TEXTURE_STEP);
    }
}
