package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Book wall at the expert boundary: an internal request is only served for the ASSUMED book.
 * Any other value is answered with 404, never 403, so the wall cannot confirm existence
 * (SPEC §3.6.2).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookScopeFilter extends OncePerRequestFilter {
    public static final String BOOK_HEADER = "X-EIA-Book";
    public static final String ASSUMED_BOOK = "ASSUMED";

    private final ObjectMapper objectMapper;

    public BookScopeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/v1/")
                && !ASSUMED_BOOK.equals(request.getHeader(BOOK_HEADER))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.bookScope(request.getRequestURI()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
