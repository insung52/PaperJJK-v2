package org.justheare.paperjjk.status;

import org.justheare.paperjjk.entity.JEntity;

/**
 * tick 기반 일반 상태이상.
 * remainingTicks 가 0 이 되면 자동 만료.
 */
public class TimedStatusEffect extends StatusEffect {

    public TimedStatusEffect(StatusEffectType type, int durationTicks) {
        super(type, durationTicks);
    }

    @Override
    protected void applyTick(JEntity entity) {
        // 기본 구현: 틱당 별도 효과 없음.
        // INFORMATION_OVERLOAD, INFINITY_STUN 등은
        // StatusEffects.isFullyStunned() 로 체크해서 외부에서 처리.
    }
}
