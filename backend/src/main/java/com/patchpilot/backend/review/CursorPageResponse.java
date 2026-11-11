package com.patchpilot.backend.review;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor) {
}
