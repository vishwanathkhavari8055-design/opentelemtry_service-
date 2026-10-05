package com.trinity.observability.dto.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * The Analytics Dashboard's log-count breakdown.
 *
 * <h2>One shape for all three levels</h2>
 *
 * <p>Category, product and microservice rows are the SAME row type. The dashboard
 * drills category → product → microservice and the columns never change — total,
 * info, warn, error, debug — so giving each level its own DTO would be three copies
 * of one structure and three code paths in the table that renders them.</p>
 */
public final class AnalyticsResponseDTO {

    private AnalyticsResponseDTO() {
    }

    /** What level the rows describe, so the UI knows what a click does next. */
    public enum Level {
        /** Rows are categories; clicking one drills to its products. */
        CATEGORY,
        /** Rows are products of one category; clicking one drills to its microservices. */
        PRODUCT,
        /** Rows are microservices. The leaf — nothing to drill into. */
        MICROSERVICE
    }

    /** One row: something in the catalog, and how many logs it produced. */
    public static class Row {

        /** Stable key — a category code, a product code, or a resource id. */
        private String key;
        /** What the operator reads. */
        private String label;

        /**
         * How many things sit DIRECTLY under this row — products in a category,
         * microservices in a product.
         *
         * <p>This is what puts "(2)" beside a product. Zero at the microservice
         * level, and zero is meaningful there rather than missing: a leaf has no
         * children.</p>
         *
         * <p>Note this is the count for the NEXT drill level, so for a category it
         * counts products, not microservices. A caller that skips the product tier
         * wants {@link #leafCount} instead — see the note there.</p>
         */
        private int childCount;

        /**
         * How many registered microservices this row's counts were aggregated from.
         *
         * <p>Separate from {@link #childCount} because the two answer different
         * questions and a screen that used the wrong one would lie. For the
         * Applications category {@code childCount} is 2 — two products — while
         * {@code leafCount} is 6, the microservices under them. A board that lists
         * every microservice in a category has to badge the card with 6, or the number
         * on the card contradicts the number of rows the click produces.</p>
         *
         * <p>Always the size of the set actually counted, at every level, so it is 1
         * on a microservice row rather than 0.</p>
         */
        private int leafCount;

        /**
         * Every log line matched, whatever its severity.
         *
         * <p>DELIBERATELY not info + warn + error. This stream also carries DEBUG,
         * TRACE and a large bucket whose severity is the literal {@code "0"} —
         * 1.38 million rows in one hour on this deployment. Making Total the sum of
         * the error-ish columns would hide the majority of the data; reporting it
         * honestly means the named columns explain part of it, and {@link #other}
         * accounts for the rest so the row still reconciles.</p>
         */
        private long total;

        private long info;
        private long warn;
        private long error;

        /**
         * DEBUG lines, counted separately rather than folded into {@link #other}.
         *
         * <p>Broken out because debug volume is a cost and a configuration signal in
         * its own right: a service left at DEBUG in production is usually the reason
         * its total dwarfs its peers', and that is invisible while debug is pooled
         * with trace and the unspecified bucket. TRACE stays in {@code other} — it is
         * a rounding error on this deployment, and a column that is always zero is
         * worse than no column.</p>
         */
        private long debug;

        /** Total minus info/warn/error/debug — trace and unspecified. Present so the
         *  row adds up, rather than leaving a reader to wonder where the rest went. */
        private long other;

        /**
         * The product this row sits under, when the row is a microservice and the
         * product tier was flattened away.
         *
         * <p>Populated only at the microservice level, and null for a resource
         * registered straight against its category — which is the honest answer for
         * Tools and Databases, since they have no product tier to belong to.</p>
         *
         * <p>Exists so flattening loses nothing, NOT so a caller can group by it again.
         * "Every microservice in Applications" is the list an operator wants; the
         * product is the tie-breaker for when two of them ship a similarly-named
         * service, and it cannot be recovered from a flat row without this field. The
         * Category Board carries it in a row's tooltip and nowhere else.</p>
         */
        private String parentKey;

        /** Display form of {@link #parentKey}. */
        private String parentLabel;

        /**
         * The telemetry identifiers this row was counted from, e.g.
         * {@code service_name=tiotesb}.
         *
         * <p>Shown in the row's tooltip. Without it a zero is unexplainable: an
         * operator cannot tell "this service is quiet" from "the catalog has it
         * registered under an identifier the logs never use".</p>
         */
        private List<String> matchedOn = new ArrayList<>();

        public String getKey()                     { return key; }
        public void setKey(String v)               { this.key = v; }
        public String getLabel()                   { return label; }
        public void setLabel(String v)             { this.label = v; }
        public int getChildCount()                 { return childCount; }
        public void setChildCount(int v)           { this.childCount = v; }
        public int getLeafCount()                  { return leafCount; }
        public void setLeafCount(int v)            { this.leafCount = v; }
        public String getParentKey()               { return parentKey; }
        public void setParentKey(String v)         { this.parentKey = v; }
        public String getParentLabel()             { return parentLabel; }
        public void setParentLabel(String v)       { this.parentLabel = v; }
        public long getTotal()                     { return total; }
        public void setTotal(long v)               { this.total = v; }
        public long getInfo()                      { return info; }
        public void setInfo(long v)                { this.info = v; }
        public long getWarn()                      { return warn; }
        public void setWarn(long v)                { this.warn = v; }
        public long getError()                     { return error; }
        public void setError(long v)               { this.error = v; }
        public long getDebug()                     { return debug; }
        public void setDebug(long v)               { this.debug = v; }
        public long getOther()                     { return other; }
        public void setOther(long v)               { this.other = v; }
        public List<String> getMatchedOn()         { return matchedOn; }
        public void setMatchedOn(List<String> v)   { this.matchedOn = v == null ? new ArrayList<>() : v; }
    }

    /**
     * The catalog's SHAPE, with no counts.
     *
     * <p>Exists to populate the dashboard's cascading pickers — Category → Product →
     * Microservice — in ONE call that touches no telemetry. Deriving the options from
     * {@link LogCounts} instead would mean a counting query per dropdown: three
     * OpenObserve round trips to fill three selects, before the table has run its
     * own. This reads the registry and returns.</p>
     *
     * <p>Only what is REGISTERED and enabled appears, which is what makes the pickers
     * honest: a product with nothing under it is not an option, because selecting it
     * could only ever show an empty table.</p>
     */
    public static class Scope {

        private List<ScopeCategory> categories = new ArrayList<>();
        private String org;
        private boolean catalogEmpty;

        public List<ScopeCategory> getCategories()       { return categories; }
        public void setCategories(List<ScopeCategory> v) { this.categories = v == null ? new ArrayList<>() : v; }
        public String getOrg()                           { return org; }
        public void setOrg(String v)                     { this.org = v; }
        public boolean isCatalogEmpty()                  { return catalogEmpty; }
        public void setCatalogEmpty(boolean v)           { this.catalogEmpty = v; }
    }

    /** A category, with the products registered under it. */
    public static class ScopeCategory {

        private String code;
        private String label;
        private List<ScopeProduct> products = new ArrayList<>();

        /**
         * Microservices registered DIRECTLY under the category, with no product.
         *
         * <p>Separate from {@link #products} rather than wrapped in a synthetic
         * product: Tools, Databases, Platform and DLH have no product tier, and
         * inventing one would put an empty level in the picker for four of the seven
         * categories. The UI skips straight from category to microservice for these.</p>
         */
        private List<ScopeResource> microservices = new ArrayList<>();

        public String getCode()                        { return code; }
        public void setCode(String v)                  { this.code = v; }
        public String getLabel()                       { return label; }
        public void setLabel(String v)                 { this.label = v; }
        public List<ScopeProduct> getProducts()        { return products; }
        public void setProducts(List<ScopeProduct> v)  { this.products = v == null ? new ArrayList<>() : v; }
        public List<ScopeResource> getMicroservices()  { return microservices; }
        public void setMicroservices(List<ScopeResource> v) { this.microservices = v == null ? new ArrayList<>() : v; }
    }

    /** A product, with the microservices registered under it. */
    public static class ScopeProduct {

        private String code;
        private String label;
        private List<ScopeResource> microservices = new ArrayList<>();

        public String getCode()                        { return code; }
        public void setCode(String v)                  { this.code = v; }
        public String getLabel()                       { return label; }
        public void setLabel(String v)                 { this.label = v; }
        public List<ScopeResource> getMicroservices()  { return microservices; }
        public void setMicroservices(List<ScopeResource> v) { this.microservices = v == null ? new ArrayList<>() : v; }
    }

    /** One registered microservice, as a picker option. */
    public static class ScopeResource {

        /** The catalog id — the key the counts endpoint returns for this row. */
        private String id;
        private String name;
        private String version;
        /** Name plus version, ready to render. */
        private String label;

        public String getId()               { return id; }
        public void setId(String v)         { this.id = v; }
        public String getName()             { return name; }
        public void setName(String v)       { this.name = v; }
        public String getVersion()          { return version; }
        public void setVersion(String v)    { this.version = v; }
        public String getLabel()            { return label; }
        public void setLabel(String v)      { this.label = v; }
    }

    /** The response: rows plus enough context for the UI's breadcrumb. */
    public static class LogCounts {

        private Level level = Level.CATEGORY;
        private List<Row> items = new ArrayList<>();

        /** Echoed so the UI can render a breadcrumb without keeping its own copy. */
        private String category;
        private String categoryLabel;
        private String product;
        private String productLabel;

        private String org;
        private String window;
        private String windowStart;
        private String windowEnd;

        /** Column-level totals, so the header can show the whole scope at a glance. */
        private Row totals = new Row();

        /**
         * False when the counts could not be trusted — OpenObserve served the window
         * only partially.
         *
         * <p>Its own flag rather than an error, because the rows that DID come back
         * are real. A dashboard that silently rounds a partial answer down to a
         * confident number is how somebody concludes a service went quiet.</p>
         */
        private boolean complete = true;

        /** True when the catalog is empty, so the UI says "register something"
         *  instead of showing an empty table that looks like a failed query. */
        private boolean catalogEmpty;

        private boolean supported = true;
        private String message;

        public Level getLevel()                    { return level; }
        public void setLevel(Level v)              { this.level = v; }
        public List<Row> getItems()                { return items; }
        public void setItems(List<Row> v)          { this.items = v == null ? new ArrayList<>() : v; }
        public String getCategory()                { return category; }
        public void setCategory(String v)          { this.category = v; }
        public String getCategoryLabel()           { return categoryLabel; }
        public void setCategoryLabel(String v)     { this.categoryLabel = v; }
        public String getProduct()                 { return product; }
        public void setProduct(String v)           { this.product = v; }
        public String getProductLabel()            { return productLabel; }
        public void setProductLabel(String v)      { this.productLabel = v; }
        public String getOrg()                     { return org; }
        public void setOrg(String v)               { this.org = v; }
        public String getWindow()                  { return window; }
        public void setWindow(String v)            { this.window = v; }
        public String getWindowStart()             { return windowStart; }
        public void setWindowStart(String v)       { this.windowStart = v; }
        public String getWindowEnd()               { return windowEnd; }
        public void setWindowEnd(String v)         { this.windowEnd = v; }
        public Row getTotals()                     { return totals; }
        public void setTotals(Row v)               { this.totals = v; }
        public boolean isComplete()                { return complete; }
        public void setComplete(boolean v)         { this.complete = v; }
        public boolean isCatalogEmpty()            { return catalogEmpty; }
        public void setCatalogEmpty(boolean v)     { this.catalogEmpty = v; }
        public boolean isSupported()               { return supported; }
        public void setSupported(boolean v)        { this.supported = v; }
        public String getMessage()                 { return message; }
        public void setMessage(String v)           { this.message = v; }
    }
}
