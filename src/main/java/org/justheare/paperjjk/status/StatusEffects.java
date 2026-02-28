package org.justheare.paperjjk.status;

import org.justheare.paperjjk.entity.JEntity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JEntity 의 상태이상 목록 관리.
 */
public class StatusEffects {

    private final List<StatusEffect> active = new ArrayList<>();

    // ── 관리 ──────────────────────────────────────────────────────────────

    public void add(StatusEffect effect) {
        // 같은 타입이 이미 있으면 교체 (BRAIN_DAMAGE 예외 — stack 누적)
        if (effect.getType() == StatusEffectType.BRAIN_DAMAGE) {
            for (StatusEffect existing : active) {
                if (existing instanceof BrainDamageEffect bd) {
                    bd.addStack(1);
                    return;
                }
            }
        } else {
            active.removeIf(e -> e.getType() == effect.getType());
        }
        active.add(effect);
    }

    public void remove(StatusEffectType type) {
        active.removeIf(e -> e.getType() == type);
    }

    public boolean has(StatusEffectType type) {
        return active.stream().anyMatch(e -> e.getType() == type);
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    public void onTick(JEntity entity) {
        Iterator<StatusEffect> it = active.iterator();
        while (it.hasNext()) {
            StatusEffect effect = it.next();
            effect.onTick(entity);
            if (effect.isExpired()) {
                effect.onExpire(entity);
                it.remove();
            }
        }
    }

    // ── 단축 체크 ─────────────────────────────────────────────────────────

    /** 모든 행동 불가 */
    public boolean isFullyStunned() {
        return has(StatusEffectType.INFINITY_STUN)
                || has(StatusEffectType.INFORMATION_OVERLOAD);
    }

    /** 술식 사용 불가 (주력 조작은 가능) */
    public boolean isTechniqueBlocked() {
        return has(StatusEffectType.TECHNIQUE_SEAL)
                || has(StatusEffectType.BURNED_TECHNIQUE);
    }

    /** 뇌손상 스택 수 */
    public int getBrainDamageCount() {
        for (StatusEffect e : active) {
            if (e instanceof BrainDamageEffect bd) return bd.getStackCount();
        }
        return 0;
    }

    /** 술식 성능 배율 (뇌손상 스택에 따라 감소) */
    public double getTechniqueEfficiencyMultiplier() {
        int stacks = getBrainDamageCount();
        if (stacks == 0) return 1.0;
        return Math.max(0.3, 1.0 - stacks * 0.1);
    }
}
