package com.intranet.util;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for parsing the raw PMS {@code /projects/owner} response, which is
 * deserialized as {@code List<Map<String, Object>>}.
 *
 * <p>The PMS payload is not guaranteed to be well-formed: a project's
 * {@code "members"} key may be missing or null, the members list may contain
 * null elements, and a member's {@code "id"} may be null or non-numeric. These
 * helpers tolerate all of those cases instead of throwing
 * {@link NullPointerException}.
 */
public final class PmsProjectUtils {

    private PmsProjectUtils() {
    }

    /**
     * Null-safe extraction of member ids from a PMS project list.
     *
     * @param projects the raw PMS project list (may be null)
     * @return the distinct member ids; never null, possibly empty
     */
    public static Set<Long> extractMemberIds(List<Map<String, Object>> projects) {
        Set<Long> memberIds = new HashSet<>();
        if (projects == null) {
            return memberIds;
        }
        for (Map<String, Object> p : projects) {
            if (p == null) {
                continue;
            }
            Object membersObj = p.get("members");
            if (!(membersObj instanceof List<?> members)) {
                continue;
            }
            for (Object mObj : members) {
                if (!(mObj instanceof Map<?, ?> m)) {   // skips null / non-map elements
                    continue;
                }
                Object idObj = m.get("id");
                if (idObj instanceof Number n) {
                    memberIds.add(n.longValue());
                }
            }
        }
        return memberIds;
    }
}
