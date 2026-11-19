package io.lakio.farfield.client;

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
        List<SettingsModel.Page> pages = SettingsModel.pages();
        Tab[] tabs = new Tab[pages.size()];
        for (int i = 0; i < tabs.length; i++) {
            OptionsList list = newList();
            for (SettingsModel.Setting setting : pages.get(i).settings()) {
                list.addBig(option(setting));
            }
            tabs[i] = new ListTab(pages.get(i).key(), list);
        }
        tabNavigationBar = TabNavigationBar.builder(tabManager, width).addTabs(tabs).build();
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
        //? if >=26.2 {
        /*tabNavigationBar.arrangeElements(width);
        *///?} else {
        tabNavigationBar.setWidth(width);
        tabNavigationBar.arrangeElements();
        //?}
        int top = tabNavigationBar.getRectangle().bottom();
        layout.setHeaderHeight(top);
        layout.arrangeElements();
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - layout.getFooterHeight() - top));
    }

    // Since 1.21.9 key presses come as one event.
    //? if >=1.21.11 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return tabNavigationBar.keyPressed(event) || super.keyPressed(event);
    }
    *///?} else {
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return tabNavigationBar.keyPressed(key) || super.keyPressed(key, scanCode, modifiers);
    }
    //?}

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        //? if >=1.21.11 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND,
                0, 0, 0.0F, 0.0F, width, layout.getHeaderHeight(), 16, 16);
        *///?} else {
        graphics.blit(CreateWorldScreen.TAB_HEADER_BACKGROUND, 0, 0, 0.0F, 0.0F, width, layout.getHeaderHeight(), 16, 16);
        //?}
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
        //? if >=26.2 {
        /*// Since 26.2 a tab gives its layout; the list is laid out by doLayout alone.
        private final net.minecraft.client.gui.layouts.FrameLayout layout = new net.minecraft.client.gui.layouts.FrameLayout();
        *///?}

        ListTab(String key, OptionsList list) {
            this.title = Component.translatable(key);
            this.list = list;
            //? if >=26.2 {
            /*layout.addChild(list);
            *///?}
        }

        //? if >=26.2 {
        /*@Override
        public net.minecraft.client.gui.layouts.Layout getLayout() {
            return layout;
        }
        *///?}

        @Override
        public Component getTabTitle() {
            return title;
        }

        //? if >=1.21.11 {
        /*@Override
        public Component getTabExtraNarration() {
            return Component.empty();
        }
        *///?}

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(list);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            list.updateSizeAndPosition(area.width(), area.height(), area.top());
        }
    }

    /** A vanilla option row for one of the model's settings. */
    private static OptionInstance<?> option(SettingsModel.Setting setting) {
        if (setting instanceof SettingsModel.Toggle t) {
            OptionInstance.TooltipSupplier<Boolean> tooltip = OptionInstance.cachedConstantTooltip(t.tooltip());
            return t.label() == null
                    ? OptionInstance.createBoolean(t.key(), tooltip, t.get().getAsBoolean(), t.set()::accept)
                    : OptionInstance.createBoolean(t.key(), tooltip, (caption, on) -> t.label().apply(on),
                    t.get().getAsBoolean(), t.set()::accept);
        }
        SettingsModel.Slider s = (SettingsModel.Slider) setting;
        return new OptionInstance<>(s.key(), OptionInstance.cachedConstantTooltip(s.tooltip()),
                (caption, step) -> Options.genericValueLabel(caption, s.label().apply(step)),
                new OptionInstance.IntRange(s.min(), s.max(), !s.applyOnRelease()),
                s.get().getAsInt(), s.set()::accept);
    }

    @Override
    public void removed() {
        super.removed();
        RenderSettings.save();
    }
}
