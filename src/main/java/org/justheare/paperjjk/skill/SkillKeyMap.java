package org.justheare.paperjjk.skill;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.scheduler.WorkScheduler;
import org.justheare.paperjjk.technique.Technique;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * 클라이언트 키 입력(x, c, v, b) → 스킬 슬롯 매핑.
 * 패킷 수신 진입점.
 */
public class SkillKeyMap {

    private final JEntity entity;
    private final Map<SkillKey, SkillSlot> bindings = new EnumMap<>(SkillKey.class);

    public SkillKeyMap(JEntity entity) {
        this.entity = entity;
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    /** 술식에 따른 기본 키 배치 적용 */
    public void initializeForTechnique(Technique technique) {
        bindings.clear();
        bindings.putAll(technique.getDefaultBindings());
    }

    /** 속박(증강) 적용 시 특정 키의 슬롯 교체 */
    public void applyBinding(SkillKey key, SkillSlot newSlot) {
        SkillSlot current = bindings.get(key);
        // 실행 중인 스킬이 있으면 먼저 종료
        if (current != null && current.isRunning()) {
            current.runningSkill.end();
        }
        bindings.put(key, newSlot);
    }

    @Nullable
    public SkillSlot getSlot(SkillKey key) {
        return bindings.get(key);
    }

    // ── 패킷 수신 진입점 ──────────────────────────────────────────────────

    /**
     * 클라이언트 키 입력 패킷 수신 시 호출.
     * PRESS  : 스킬 생성 + 충전 시작
     * RELEASE: 충전 종료 → 발동 전환
     */
    public void onKeyPress(SkillKey key, KeyEventType event) {
        SkillSlot slot = bindings.get(key);
        if (slot == null) return;

        // 술식 봉인/행동불가 상태면 입력 무시
        if (event == KeyEventType.PRESS && entity.isTechniqueBlocked()) return;
        if (event == KeyEventType.PRESS && entity.isFullyStunned()) return;

        switch (event) {
            case PRESS -> handlePress(slot);
            case RELEASE -> handleRelease(slot);
        }
    }

    private void handlePress(SkillSlot slot) {
        // 이미 실행 중인 스킬이 있고 재충전 가능하면 재충전 시작
        if (slot.isRunning() && slot.rechargeable && slot.runningSkill.isActive()) {
            slot.runningSkill.startRecharging();
            return;
        }

        // 새 스킬 생성 (SkillFactory 에서 skillId 로 생성)
        ActiveSkill skill = SkillFactory.create(slot.skillId, entity);
        if (skill == null) return;

        slot.runningSkill = skill;
        entity.addActiveSkill(skill);
        WorkScheduler.getInstance().register(skill);
    }

    private void handleRelease(SkillSlot slot) {
        if (slot.runningSkill == null) return;
        if (slot.chargeable && slot.runningSkill.isCharging()) {
            slot.runningSkill.stopCharging();
        }
    }
}
