package org.justheare.paperjjk.network;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UUID → JEntity 매핑 관리. 싱글턴.
 * 기존 PaperJJK.jobjects 목록을 대체.
 *
 * 사용:
 *   JEntityManager.instance.register(jPlayer);
 *   JEntityManager.instance.getPlayer(uuid);
 */
public class JEntityManager {

    public static JEntityManager instance;

    private final Map<UUID, JEntity> entities = new ConcurrentHashMap<>();

    private JEntityManager() {}

    public static void init() {
        instance = new JEntityManager();
    }

    // ── 등록/해제 ─────────────────────────────────────────────────────────

    public void register(JEntity entity) {
        entities.put(entity.uuid, entity);
    }

    public void unregister(UUID uuid) {
        entities.remove(uuid);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public JEntity get(UUID uuid) {
        return entities.get(uuid);
    }

    public JPlayer getPlayer(UUID uuid) {
        JEntity entity = entities.get(uuid);
        return entity instanceof JPlayer jp ? jp : null;
    }

    public Collection<JEntity> all() {
        return entities.values();
    }

    public int size() {
        return entities.size();
    }
}
