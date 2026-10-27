package com.anorys.pmm.cli.render;

import java.util.ArrayList;
import java.util.List;

/** A plain-text table with left-aligned columns, except the ones declared as numeric. */
public final class Table {

    private final List<String> headers;
    private final boolean[] numeric;
    private final List<List<String>> rows = new ArrayList<>();

    public Table(String... headers) {
        this.headers = List.of(headers);
        this.numeric = new boolean[headers.length];
    }

    public Table numeric(int... columns) {
        for (int column : columns) {
            numeric[column] = true;
        }
        return this;
    }

    public Table row(String... cells) {
        if (cells.length != headers.size()) {
            throw new IllegalArgumentException("Expected " + headers.size() + " cells, got " + cells.length);
        }
        rows.add(List.of(cells));
        return this;
    }

    public String render() {
        int[] widths = new int[headers.size()];
        for (int c = 0; c < widths.length; c++) {
            widths[c] = headers.get(c).length();
            for (List<String> row : rows) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        StringBuilder out = new StringBuilder();
        line(out, headers, widths);
        rows.forEach(row -> line(out, row, widths));
        return out.toString();
    }

    private void line(StringBuilder out, List<String> cells, int[] widths) {
        StringBuilder line = new StringBuilder(" ");
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            String padding = " ".repeat(widths[c] - cell.length());
            line.append(numeric[c] ? padding + cell : cell + padding).append("  ");
        }
        out.append(line.toString().stripTrailing()).append(System.lineSeparator());
    }
}
