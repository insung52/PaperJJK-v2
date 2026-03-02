package org.justheare.paperjjk.barrier;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * 활성 영역전개(DomainExpansion)의 전역 레지스트리. 싱글턴.
 *
 * 신규 영역 등록 시 충돌 감지 및 밀어내기 싸움 개시를 담당.
 * WorkScheduler.onTick() 에서 매 틱 호출.
 */
public class DomainManager {

    public static DomainManager instance;

    /** DOMAIN_VISUAL 브로드캐스트 최소 반경 (m) */
    public static final double BROADCAST_RANGE = 300.0;

    private final List<DomainExpansion> active = new ArrayList<>();

    public static void init() {
        instance = new DomainManager();
    }

    // ── 등록/해제 ─────────────────────────────────────────────────────────

    /**
     * 영역전개를 레지스트리에 등록하고 기존 영역과의 충돌을 감지한다.
     * 겹치는 영역이 있으면 밀어내기 싸움을 개시한다.
     */
    public void register(DomainExpansion domain) {
        Location domainCenter = domain.getCaster().entity.getLocation();

        for (DomainExpansion existing : new ArrayList<>(active)) {
            // 이미 종료 중인 영역은 충돌 대상에서 제외
            DomainExpansion.DomainPhase phase = existing.getDomainPhase();
            if (phase == DomainExpansion.DomainPhase.CLOSING
                    || phase == DomainExpansion.DomainPhase.DONE) continue;

            Location existingCenter = existing.getCaster().entity.getLocation();

            // 다른 월드는 충돌 없음
            if (domainCenter.getWorld() == null
                    || !domainCenter.getWorld().equals(existingCenter.getWorld())) continue;

            double dist = domainCenter.distance(existingCenter);
            if (dist < domain.getRange() + existing.getRange()) {
                initiateClash(existing, domain);
            }
        }

        active.add(domain);
    }

    public void unregister(DomainExpansion domain) {
        active.remove(domain);
    }

    // ── 매 틱 정리 ────────────────────────────────────────────────────────

    /** DONE 상태인 영역전개를 목록에서 정리 */
    public void onTick() {
        active.removeIf(d -> d.getDomainPhase() == DomainExpansion.DomainPhase.DONE);
    }

    // ── 충돌 감지 ─────────────────────────────────────────────────────────

    /**
     * 두 영역 간 밀어내기 싸움을 개시한다.
     *
     * 충돌 규칙 (background.md):
     *   일반 vs 일반: 먼저 전개한 것(existing) = 방어자
     *   결없영 vs 일반: 일반 영역이 항상 방어자 (순서 무관)
     *   결없영 vs 결없영: 직접 싸움 없음 (교집합 구역만 필중 무력화)
     */
    private void initiateClash(DomainExpansion existing, DomainExpansion incoming) {
        // 결없영 vs 결없영: 밀어내기 싸움 없음
        if (existing.isOpen() && incoming.isOpen()) return;

        DomainExpansion defender;
        DomainExpansion attacker;

        if (existing.isOpen()) {
            // 결없영(existing) vs 일반(incoming): 일반이 방어자
            defender = incoming;
            attacker = existing;
        } else if (incoming.isOpen()) {
            // 일반(existing) vs 결없영(incoming): 일반이 방어자
            defender = existing;
            attacker = incoming;
        } else {
            // 일반 vs 일반: 먼저 전개한 것이 방어자
            defender = existing;
            attacker = incoming;
        }

        defender.startClash(attacker);
        attacker.startClashAsAttacker(defender);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public List<DomainExpansion> getActiveDomains() {
        return new ArrayList<>(active);
    }

    /** 특정 위치에 해당하는 결계 블록을 가진 영역전개를 반환 (없으면 null) */
    public DomainExpansion getDomainForBarrierBlock(Location loc) {
        for (DomainExpansion domain : active) {
            if (!domain.isOpen() && domain.containsBarrierBlock(loc)) return domain;
        }
        return null;
    }
}
