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
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Farfield's settings screen for Minecraft 1.20.1 (the shared one needs the
 * header and footer layout and the resizable options list of 1.20.5 on):
 * the same tabs from {@link SettingsModel}, each an options list that the
 * screen shows under the tab bar when its tab is selected. Changes apply at
 * once, except the sliders the model applies on release (1.20.1's sliders
 * apply while dragged), which apply when the screen closes; everything is
 * saved to {@code config/farfield-client.properties} then.
 */
public final class FarfieldSettingsScreen extends OptionsSubScreen {

    private static final int FOOTER = 36;

    /** The tab open when the screen last closed, this session. */
    private static int lastTab;

    /** Values of the sliders applied on release, by setting, until the screen closes. */
    private static final Map<SettingsModel.Slider, Integer> pending = new LinkedHashMap<>();

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<OptionsList> lists = new ArrayList<>();
    private TabNavigationBar tabNavigationBar;
    private OptionsList current;

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
        current = null;
        List<SettingsModel.Page> pages = SettingsModel.pages();
        Tab[] tabs = new Tab[pages.size()];
        for (int i = 0; i < tabs.length; i++) {
            OptionsList list = new OptionsList(minecraft, width, height, 24, height - FOOTER, 25);
            for (SettingsModel.Setting setting : pages.get(i).settings()) {
                list.addBig(option(setting));
            }
            lists.add(list);
            tabs[i] = new ListTab(pages.get(i).key(), list);
        }
        tabNavigationBar = TabNavigationBar.builder(tabManager, width).addTabs(tabs).build();
        addRenderableWidget(tabNavigationBar);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
        tabNavigationBar.selectTab(Math.min(lastTab, lists.size() - 1), false);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (tabNavigationBar == null) {
            return;
        }
        tabNavigationBar.setWidth(width);
        tabNavigationBar.arrangeElements();
        int top = tabNavigationBar.getRectangle().bottom();
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - FOOTER - top));
    }

    /** Shows one tab's list in the tab area (called by its tab's layout). */
    private void show(OptionsList list, ScreenRectangle area) {
        if (current != list) {
            if (current != null) {
                removeWidget(current);
            }
            current = list;
            addWidget(list);
        }
        list.updateSize(area.width(), height, area.top(), area.bottom());
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return tabNavigationBar.keyPressed(key) || super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        if (current != null) {
            current.render(graphics, mouseX, mouseY, partialTick);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        applyPending();
        Tab tab = tabManager.getCurrentTab();
        for (int i = 0; i < lists.size(); i++) {
            if (tab instanceof ListTab t && t.list == lists.get(i)) {
                lastTab = i;
            }
        }
        minecraft.setScreen(lastScreen);
    }

    @Override
    public void removed() {
        super.removed();
        applyPending();
        RenderSettings.save();
    }

    private static void applyPending() {
        pending.forEach((slider, value) -> slider.set().accept(value));
        pending.clear();
    }

    /** A tab showing one options list over the whole tab area. */
    private final class ListTab implements Tab {
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

        /** The list is not a widget in 1.20.1: the screen adds it itself ({@link #show}). */
        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            show(list, area);
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
        Consumer<Integer> set = s.applyOnRelease() ? value -> pending.put(s, value) : s.set()::accept;
        return new OptionInstance<>(s.key(), OptionInstance.cachedConstantTooltip(s.tooltip()),
                (caption, step) -> Options.genericValueLabel(caption, s.label().apply(step)),
                new OptionInstance.IntRange(s.min(), s.max()),
                s.get().getAsInt(), set);
    }
}
