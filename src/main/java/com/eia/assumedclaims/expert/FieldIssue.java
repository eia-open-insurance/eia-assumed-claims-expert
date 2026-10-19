package com.eia.assumedclaims.expert;

/**
 * A single field-level issue in a structured error response.
 */
public record FieldIssue(String field, String code, String message) {}
