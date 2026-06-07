package com.hobbietrades.backend.util;

import java.util.Set;

public final class HobbyCategories {

    public static final Set<String> ALLOWED = Set.of("Cameras", "Instruments");
    public static final int REQUIRED_HOBBY_PHOTOS = 5;

    private HobbyCategories() {}

    public static boolean isAllowed(String category) {
        return category != null && ALLOWED.contains(category);
    }
}
