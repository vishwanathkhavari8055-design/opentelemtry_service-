package com.trinity.observability.controller;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.service.StreamCatalogService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * What streams this tenant has, per signal type.
 *
 * <p>Exists so the stream pickers on Logs and Traces can be populated from
 * OpenObserve instead of from a constant in the UI, which is what they used to be:
 * a hardcoded {@code ['default']} that never grew, so a tenant with fourteen streams
 * could only ever look at one.</p>
 *
 * <p>Separate from {@code /api/alerts/streams}, which serves the same data to the
 * alert editor. A neutral path is not cosmetic here — the Logs screen calling an
 * alerts endpoint to fill its own dropdown is the kind of coupling that makes an
 * alerts refactor break logs. The alerts route is left exactly as it is.</p>
 */
@RestController
@RequestMapping("/api/streams")
@Tag(name = "Streams", description = "Stream and column discovery")
public class StreamsController {

    private static final Logger log = LogManager.getLogger(StreamsController.class);

    private final StreamCatalogService catalog;

    public StreamsController(StreamCatalogService catalog) {
        this.catalog = catalog;
    }

    /** The envelope every list route in this service uses. */
    public record NameList(List<String> items, String type, int total) {
    }

    @GetMapping
    @Operation(summary = "Stream names of a signal type",
            description = "type = logs | traces | metrics. Read live from OpenObserve, so a "
                    + "stream created by a new exporter appears without a code change. An empty "
                    + "list means the lookup could not be made (OpenObserve disabled or "
                    + "unreachable) rather than that the tenant has none — callers should fall "
                    + "back to the configured default stream rather than showing an empty picker.")
    public NameList list(@RequestParam(name = "type", defaultValue = "logs") String type) {
        log.info("GET /api/streams?type={} received", type);
        List<String> names = new ArrayList<>(catalog.list(type));
        return new NameList(names, type, names.size());
    }

    @GetMapping("/{streamName}/fields")
    @Operation(summary = "Column names on one stream",
            description = "What the stream actually carries. An empty list is normal for a "
                    + "stream that has never been written to. This is the same information the "
                    + "log reader uses to decide whether a stream can be read with the OTel "
                    + "projection or has to be read adaptively.")
    public NameList fields(@PathVariable("streamName") String streamName,
            @RequestParam(name = "type", defaultValue = "logs") String type) {
        log.info("GET /api/streams/{}/fields?type={} received", streamName, type);
        List<String> names = new ArrayList<>(catalog.fields(type, streamName));
        return new NameList(names, type, names.size());
    }
}
