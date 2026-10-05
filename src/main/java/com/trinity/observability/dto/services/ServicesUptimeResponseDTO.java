package com.trinity.observability.dto.services;

import java.util.List;

/**
 * Response wrapper for {@code GET /api/services/uptime}.
 *
 * <p>The window metadata (start/end/threshold/stream) is echoed back so the
 * UI's Services Uptime card can show "last 1h from jvm_memory_used, threshold
 * 60s" without re-deriving it from the query string.</p>
 */
public class ServicesUptimeResponseDTO {

    private List<ServiceUptimeItemDTO> items;
    private String  windowStart;
    private String  windowEnd;
    private int     livenessThresholdSec;
    private String  heartbeatStream;
    private String  backend;
    /**
     * True when uptime detection was actually performed against OpenObserve.
     * Set to false on the ES path (not implemented per the design plan) so the
     * UI can render an informative empty state rather than a network error.
     */
    private boolean supported;

    /** Default constructor for framework instantiation. */
    public ServicesUptimeResponseDTO() {
        // framework instantiation
    }

    public List<ServiceUptimeItemDTO> getItems()          { return items; }
    public void setItems(List<ServiceUptimeItemDTO> v)    { this.items = v; }

    public String getWindowStart()                        { return windowStart; }
    public void   setWindowStart(String v)                { this.windowStart = v; }

    public String getWindowEnd()                          { return windowEnd; }
    public void   setWindowEnd(String v)                  { this.windowEnd = v; }

    public int    getLivenessThresholdSec()               { return livenessThresholdSec; }
    public void   setLivenessThresholdSec(int v)          { this.livenessThresholdSec = v; }

    public String getHeartbeatStream()                    { return heartbeatStream; }
    public void   setHeartbeatStream(String v)            { this.heartbeatStream = v; }

    public String getBackend()                            { return backend; }
    public void   setBackend(String v)                    { this.backend = v; }

    public boolean isSupported()                          { return supported; }
    public void    setSupported(boolean v)                { this.supported = v; }
}
