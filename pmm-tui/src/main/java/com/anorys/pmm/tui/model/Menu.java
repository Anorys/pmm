package com.anorys.pmm.tui.model;

import com.anorys.pmm.tui.model.DashboardState.DialogLine;
import com.anorys.pmm.tui.theme.Tone;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A centered list to pick from: the update strategies, the branches to switch to. The selected item is
 * described under the list; a filterable menu narrows the list as the user types. Only read and
 * changed on the render thread.
 */
public final class Menu {

    /**
     * @param id      what the dashboard acts on (a strategy id, a branch name)
     * @param detail  shown after the label, dimmed
     * @param note    shown at the right end of the row, in the tone's color
     * @param enabled can be chosen; a disabled item is listed with why in its description
     */
    public record Item(String id, String label, String detail, Tone tone, String note, boolean enabled) {
    }

    /** A key shown at the bottom: {@code t} "target: base". */
    public record Hint(String key, String label) {
    }

    private final String title;
    private final List<DialogLine> header;
    private final List<Item> items;
    private final Function<Item, List<DialogLine>> describe;
    private final List<Hint> hints;
    private final Consumer<Item> onChoose;
    private final Predicate<Character> onKey;
    private final boolean filterable;
    private String filter = "";
    private int selected;

    /**
     * @param onKey handles a key of a non-filterable menu, true when it did something
     */
    public Menu(String title, List<DialogLine> header, List<Item> items, Function<Item, List<DialogLine>> describe,
                List<Hint> hints, boolean filterable, Consumer<Item> onChoose, Predicate<Character> onKey) {
        this.title = title;
        this.header = List.copyOf(header);
        this.items = List.copyOf(items);
        this.describe = describe;
        this.hints = List.copyOf(hints);
        this.filterable = filterable;
        this.onChoose = onChoose;
        this.onKey = onKey;
    }

    public String title() {
        return title;
    }

    public List<DialogLine> header() {
        return header;
    }

    public List<Hint> hints() {
        return hints;
    }

    public boolean filterable() {
        return filterable;
    }

    public String filter() {
        return filter;
    }

    /** The items matching the filter. */
    public List<Item> visible() {
        if (filter.isEmpty()) {
            return items;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        return items.stream().filter(i -> i.label().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    public int selectedIndex() {
        List<Item> visible = visible();
        return visible.isEmpty() ? -1 : Math.min(selected, visible.size() - 1);
    }

    public Item selected() {
        int i = selectedIndex();
        return i < 0 ? null : visible().get(i);
    }

    public void select(String id) {
        List<Item> visible = visible();
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id().equals(id)) {
                selected = i;
            }
        }
    }

    public void move(int delta) {
        int size = visible().size();
        if (size > 0) {
            selected = Math.floorMod(selectedIndex() + delta, size);
        }
    }

    public List<DialogLine> description() {
        Item item = selected();
        return item == null ? List.of() : describe.apply(item);
    }

    public void type(char c) {
        filter += c;
        selected = 0;
    }

    public void erase() {
        if (!filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            selected = 0;
        }
    }

    /** Runs the choice when the selected item is enabled; true if it ran. */
    public boolean choose() {
        Item item = selected();
        if (item == null || !item.enabled()) {
            return false;
        }
        onChoose.accept(item);
        return true;
    }

    public boolean key(char c) {
        return onKey != null && onKey.test(c);
    }
}
