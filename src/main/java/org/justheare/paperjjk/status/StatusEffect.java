package org.justheare.paperjjk.status;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 상태이상 추상 기반.
 * tick 기반: remainingTicks 로 만료 관리.
 * stack 기반(BRAIN_DAMAGE): BrainDamageEffect 에서 별도 처리.
 */
public abstract class StatusEffect {

    protected final StatusEffectType type;
    protected int remainingTicks;   // -1 = 영구 (BRAIN_DAMAGE 등)

    protected StatusEffect(StatusEffectType type, int durationTicks) {
        this.type = type;
        this.remainingTicks = durationTicks;
    }

    public StatusEffectType getType() { return type; }

    public boolean isExpired() {
        return remainingTicks == 0;
    }

    public void onTick(JEntity entity) {
        if (remainingTicks > 0) remainingTicks--;
        applyTick(entity);
    }

    /** 매 틱 효과 적용 */
    protected abstract void applyTick(JEntity entity);

    /** 만료 시 처리 */
    public void onExpire(JEntity entity) {}
}
