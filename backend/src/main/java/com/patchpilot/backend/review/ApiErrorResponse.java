package com.patchpilot.backend.review;

import java.net.URI;

public record ApiErrorResponse(URI type, String title, int status, String detail, String code, String traceId) {
}
