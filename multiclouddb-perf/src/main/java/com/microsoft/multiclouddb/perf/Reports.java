// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared helpers for the Markdown and HTML report renderers. */
final class Reports {

    /** Canonical provider display order; any extra providers are appended in first-seen order. */
    static final List<String> CANONICAL = List.of("cosmos", "dynamo", "spanner");

    private Reports() {
    }

    /** Providers present in the stats, canonical ones first. */
    static List<String> providerOrder(List<StatRow> stats) {
        Set<String> present = new LinkedHashSet<>();
        for (StatRow s : stats) {
            present.add(s.provider());
        }
        List<String> order = new ArrayList<>();
        for (String p : CANONICAL) {
            if (present.remove(p)) {
                order.add(p);
            }
        }
        order.addAll(present);
        return order;
    }

    /** Compact number formatting: whole numbers for large values, more precision for small. */
    static String num(double x) {
        if (x >= 100) {
            return String.format(Locale.ROOT, "%.0f", x);
        }
        if (x >= 10) {
            return String.format(Locale.ROOT, "%.1f", x);
        }
        return String.format(Locale.ROOT, "%.2f", x);
    }

    static String numOrDash(Double x) {
        return x == null ? "—" : num(x);
    }

    /** Short human explanation of what a given operation row measures. */
    static String opMeasures(String op) {
        if (op == null) {
            return "";
        }
        switch (op) {
            case "create": return "Insert one new item by key";
            case "read":   return "Point-read one item by its key";
            case "update": return "Replace one existing item by key";
            case "upsert": return "Insert-or-replace one item by key";
            case "delete": return "Delete one item by key";
            case "query":  return "Run a filtered query, fetch one page";
            default:       return op;
        }
    }

    /** HTML entity-escaping for text nodes / attribute values. */
    static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
