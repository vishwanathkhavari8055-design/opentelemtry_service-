package com.trinity.observability;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.trinity.observability.config.StartupConfigBanner;

@SpringBootApplication
public class ObservabilityBackendApplication {

    /**
     * JVM zone IDs PostgreSQL's tzdata does not recognise, mapped to the modern
     * IANA name for the very same zone.
     *
     * <p>The PostgreSQL JDBC driver sends {@code TimeZone.getDefault().getID()}
     * as a startup parameter, and the server rejects an unknown value with a
     * FATAL before the connection is usable:</p>
     *
     * <pre>FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"</pre>
     *
     * <p>A JVM on a machine set to Indian Standard Time still reports the
     * retired alias {@code Asia/Calcutta}, while a current PostgreSQL build
     * only knows {@code Asia/Kolkata}. There is no driver or URL option to
     * suppress the parameter, so it has to be corrected before the first
     * connection — which means before Spring starts.</p>
     *
     * <p>This is a rename, not a policy: both IDs denote the identical zone
     * with the identical offset and no DST, so nothing about how this service
     * formats or stores a timestamp changes. The alternative — forcing UTC —
     * would silently shift every locally-rendered time by five and a half
     * hours.</p>
     */
    private static final String LEGACY_ZONE_ID  = "Asia/Calcutta";
    private static final String CURRENT_ZONE_ID = "Asia/Kolkata";

    public static void main(String[] args) {
        normaliseLegacyTimeZoneId();

        SpringApplication application = new SpringApplication(ObservabilityBackendApplication.class);

        /*
         * The configuration banner, registered before the run begins.
         *
         * It listens for ApplicationEnvironmentPreparedEvent, which fires before
         * the application context is built — so it cannot be a @Component, and
         * component scanning would be too late anyway. Registering it here is
         * what makes it print on EVERY start: a port clash or an unreachable
         * database aborts startup long before any bean exists, and that is
         * precisely when someone needs to see which host and which database this
         * instance was pointed at.
         */
        application.addListeners(new StartupConfigBanner());

        application.run(args);
    }

    /** No-op unless the JVM defaulted to a zone ID PostgreSQL cannot parse. */
    private static void normaliseLegacyTimeZoneId() {
        if (LEGACY_ZONE_ID.equals(TimeZone.getDefault().getID())) {
            TimeZone.setDefault(TimeZone.getTimeZone(CURRENT_ZONE_ID));
        }
    }
}
