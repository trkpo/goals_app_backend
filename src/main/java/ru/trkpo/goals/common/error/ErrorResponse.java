package ru.trkpo.goals.common.error;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** Единый конверт ошибки (ADR 0001, п. 1). {@code field} отдаётся всегда, пусть и как null. */
public record ErrorResponse(
    String code,
    String message,
    @Nullable String field,
    @JsonProperty("request_id") @Nullable String requestId) {}
