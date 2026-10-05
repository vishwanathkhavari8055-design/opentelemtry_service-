package com.trinity.observability.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class RequestIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String requestId = httpRequest.getHeader("X-Request-ID");
                
                if (requestId == null || requestId.trim().isEmpty()) {
                    requestId = UUID.randomUUID().toString();
                }
                
                ThreadContext.put("requestId", requestId);
            }
            
            chain.doFilter(request, response);
            
        } finally {
            ThreadContext.clearAll();
        }
    }
}
