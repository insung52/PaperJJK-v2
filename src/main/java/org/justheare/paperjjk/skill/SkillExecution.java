package org.justheare.paperjjk.skill;

/**
 * WorkScheduler 에 등록되어 매 틱 실행되는 단위.
 * ActiveSkill 이 이 인터페이스를 구현.
 * Bukkit.scheduleSyncRepeatingTask() 를 직접 사용하지 않음.
 */
public interface SkillExecution {

    /** WorkScheduler 가 매 틱 호출 */
    void tick();

    /** ENDED 상태면 true — WorkScheduler 가 자동 제거 */
    boolean isDone();

    /**
     * WFQ 우선순위.
     * 0 = CRITICAL (데미지 등 게임 상태에 직접 영향)
     * 1 = NORMAL   (블럭 파괴 등)
     * 2 = COSMETIC (파티클, 사운드 — 예산 초과 시 드롭 허용)
     */
    int getPriority();

    /**
     * Phase 2: pendingBreaks 큐에서 최대 budget 개만큼 block.setType(AIR, false) 실행.
     * 기본 구현은 no-op (블록 파괴가 없는 스킬용).
     * @return 실제로 소비한 토큰 수
     */
    default int flushBlocks(int budget) { return 0; }
}
