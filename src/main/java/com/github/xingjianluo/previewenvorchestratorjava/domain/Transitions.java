package com.github.xingjianluo.previewenvorchestratorjava.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 生命周期状态机：定义合法迁移，非法/重复迁移在此被拒绝。
 *
 * <pre>
 * CREATING   -> STOPPED | DESTROYING        （开通成功 / 开通失败清理）
 * RUNNING    -> STOPPED | DESTROYING
 * STOPPED    -> RUNNING | DESTROYING
 * DESTROYING -> DESTROYED
 * DESTROYED  -> （终态）
 * </pre>
 */
public final class Transitions {

    private static final Map<EnvState, Set<EnvState>> ALLOWED = new EnumMap<>(EnvState.class);

    static {
        ALLOWED.put(EnvState.CREATING, Set.of(EnvState.STOPPED, EnvState.DESTROYING));
        ALLOWED.put(EnvState.RUNNING, Set.of(EnvState.STOPPED, EnvState.DESTROYING));
        ALLOWED.put(EnvState.STOPPED, Set.of(EnvState.RUNNING, EnvState.DESTROYING));
        ALLOWED.put(EnvState.DESTROYING, Set.of(EnvState.DESTROYED));
        ALLOWED.put(EnvState.DESTROYED, Set.of());
    }

    private Transitions() {
    }

    public static boolean isAllowed(EnvState from, EnvState to) {
        return ALLOWED.get(from).contains(to);
    }

    /** 判定依据文本，用于日志与错误信息。 */
    public static String explain(EnvState from, EnvState to) {
        return "transition " + from + " -> " + to
                + (isAllowed(from, to) ? " is allowed" : " is NOT allowed; allowed targets from "
                + from + " are " + ALLOWED.get(from));
    }
}
