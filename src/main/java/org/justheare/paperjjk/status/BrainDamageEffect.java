package org.justheare.paperjjk.status;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 뇌손상 — stack 기반 영구 누적 상태이상.
 * remainingTicks = -1 (만료 없음).
 * 반전술식 뇌 복구 증강으로만 stack 감소.
 */
public class BrainDamageEffect extends StatusEffect {

    private int stackCount;

    public BrainDamageEffect(int initialStacks) {
        super(StatusEffectType.BRAIN_DAMAGE, -1);
        this.stackCount = initialStacks;
    }

    @Override
    public boolean isExpired() {
        return stackCount <= 0;
    }

    @Override
    protected void applyTick(JEntity entity) {
        // 뇌손상은 매 틱 직접 효과 없음.
        // StatusEffects.getTechniqueEfficiencyMultiplier() 에서 스택 수를 참조해 패널티 적용.
    }

    public void addStack(int amount) {
        stackCount += amount;
    }

    /** 반전술식 뇌 복구 증강 사용 시 호출 */
    public void reduceStack(int amount) {
        stackCount = Math.max(0, stackCount - amount);
    }

    public int getStackCount() { return stackCount; }
}
