package com.trinity.observability.dto.metrics;

import java.util.List;

/**
 * Every metric name the instance knows about, with its Prometheus metadata and
 * the group it was sorted into.
 *
 * Sourced from the Prometheus-compatible API OpenObserve exposes at
 * {@code /api/{org}/prometheus/api/v1/*} — NOT from {@code /streams?type=metrics}.
 * Both return the same names, but only the metadata endpoint carries the
 * type, help text and unit, and a metrics explorer without those forces the
 * user to guess whether a series is a counter (needs rate()) or a gauge.
 *
 * <h2>Why the response carries grouping as well as names</h2>
 *
 * <p>This instance returns 3,215 names in one list, mixing our own services with
 * WSO2's JMX beans, Postgres, Kafka, Kubernetes and OpenObserve's own internals.
 * {@link com.trinity.observability.util.MetricClassifier} assigns each item a
 * {@code group} and a {@code technology}, and {@link #getGroups()} carries the
 * counts so the UI can render facets without walking the list itself.</p>
 *
 * <p>The classification is computed here rather than in the frontend on purpose:
 * it is a fact about this deployment's exporters, it has to stay in step with the
 * operator-supplied rules in
 * {@link com.trinity.observability.config.MetricClassificationProperties}, and
 * every other consumer of this endpoint gets it for free.</p>
 */
public class MetricCatalogDTO {

    private List<Item> items;
    private int total;
    private boolean supported = true;
    private String error;
    /** Facet counts, in display order. Empty rather than null when unavailable. */
    private List<Group> groups;

    public static class Item {
        private String name;
        /** counter | gauge | histogram | summary | unknown */
        private String type;
        private String help;
        private String unit;
        /** Group key — microservice | middleware | platform | observability | runtime | other. */
        private String group;
        /** The product or library this metric comes from, e.g. "PostgreSQL". */
        private String technology;
        /**
         * True when metrics in this group are emitted by several unrelated
         * producers ({@code process_*}, {@code system_*}, {@code go_*},
         * {@code up}), so the name does not identify an owner and only a
         * {@code service_name} filter separates them.
         */
        private boolean shared;

        public Item() {}

        public Item(String name, String type, String help, String unit) {
            this.name = name;
            this.type = type;
            this.help = help;
            this.unit = unit;
        }

        public String getName()       { return name; }
        public String getType()       { return type; }
        public String getHelp()       { return help; }
        public String getUnit()       { return unit; }
        public String getGroup()      { return group; }
        public String getTechnology() { return technology; }
        public boolean isShared()     { return shared; }
        public void setName(String v)       { this.name = v; }
        public void setType(String v)       { this.type = v; }
        public void setHelp(String v)       { this.help = v; }
        public void setUnit(String v)       { this.unit = v; }
        public void setGroup(String v)      { this.group = v; }
        public void setTechnology(String v) { this.technology = v; }
        public void setShared(boolean v)    { this.shared = v; }
    }

    /** One facet: a group, how many metrics landed in it, and its technologies. */
    public static class Group {
        private String key;
        private String label;
        private int count;
        private List<Technology> technologies;

        public Group() {}

        public Group(String key, String label, int count, List<Technology> technologies) {
            this.key = key;
            this.label = label;
            this.count = count;
            this.technologies = technologies;
        }

        public String getKey()                    { return key; }
        public String getLabel()                  { return label; }
        public int getCount()                     { return count; }
        public List<Technology> getTechnologies() { return technologies; }
        public void setKey(String v)              { this.key = v; }
        public void setLabel(String v)            { this.label = v; }
        public void setCount(int v)               { this.count = v; }
        public void setTechnologies(List<Technology> v) { this.technologies = v; }
    }

    /** One technology within a group, with its metric count. */
    public static class Technology {
        private String name;
        private int count;

        public Technology() {}

        public Technology(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName()       { return name; }
        public int getCount()         { return count; }
        public void setName(String v) { this.name = v; }
        public void setCount(int v)   { this.count = v; }
    }

    public List<Item> getItems()   { return items; }
    public int getTotal()          { return total; }
    public boolean isSupported()   { return supported; }
    public String getError()       { return error; }
    public List<Group> getGroups() { return groups; }
    public void setItems(List<Item> v)   { this.items = v; }
    public void setTotal(int v)          { this.total = v; }
    public void setSupported(boolean v)  { this.supported = v; }
    public void setError(String v)       { this.error = v; }
    public void setGroups(List<Group> v) { this.groups = v; }
}
