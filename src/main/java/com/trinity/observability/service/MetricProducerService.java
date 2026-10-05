package com.trinity.observability.service;

import com.trinity.observability.dto.metrics.MetricProducersDTO;

/**
 * The catalogue, inverted: which service exposes which metrics.
 *
 * <p>Answering this needs a sweep of every metric name — see
 * {@link com.trinity.observability.config.MetricProducerProperties} for why no
 * single query can do it on this backend — so the answer is built in the
 * background and cached. Callers never block on a build.</p>
 */
public interface MetricProducerService {

    /**
     * The current index.
     *
     * <p>Returns immediately, always. If nothing is cached and nothing is
     * running, a build is started and the response says
     * {@code building: true} with progress; poll for the result. A cached index
     * past its TTL is returned as-is with its {@code ageSeconds} while a
     * refresh runs behind it, because a ten-minute-old answer beats a spinner.</p>
     */
    MetricProducersDTO getProducers();

    /**
     * Discard the cached index and start a fresh build.
     *
     * <p>For the operator who has just deployed a service and does not want to
     * wait out the TTL to see it appear.</p>
     */
    MetricProducersDTO refresh();
}
