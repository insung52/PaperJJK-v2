package org.justheare.paperjjk.scheduler;

import org.bukkit.Bukkit;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.skill.SkillExecution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 모든 스킬 실행을 관리하는 스케줄러. 싱글턴.
 * 기존: 스킬마다 Bukkit.scheduleSyncRepeatingTask() 1개
 * v2:  WorkScheduler 단일 Bukkit 태스크 → WFQ 로 분배
 *
 * config.yml 에서 budgetPerTick 설정 가능.
 */
public class WorkScheduler {

    private static WorkScheduler instance;

    private final List<SkillExecution> executions = new ArrayList<>();

    /**
     * ENDED 된 스킬의 남은 pendingBreaks 를 소화하는 큐.
     * tick() 은 더 이상 호출되지 않고, flushBlocks() 만 매 틱 호출.
     * pendingBreaks 가 비면 (flushBlocks 가 0 반환) 자동 제거.
     */
    private final List<SkillExecution> draining = new ArrayList<>();

    private int taskId = -1;

    /** 활성 스킬 WFQ 틱당 예산 */
    private int budgetPerTick = 3000;

    /** draining 큐 틱당 예산 (활성 스킬과 독립) */
    private int drainBudgetPerTick = 3000;

    private WorkScheduler() {}

    public static WorkScheduler getInstance() {
        if (instance == null) instance = new WorkScheduler();
        return instance;
    }

    // ── 시작 / 종료 ───────────────────────────────────────────────────────

    public void start() {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                PaperJJK.instance, this::onTick, 1L, 1L);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        executions.clear();
        draining.clear();
    }

    // ── 등록 ──────────────────────────────────────────────────────────────

    public void register(SkillExecution exec) {
        executions.add(exec);
    }

    public void unregister(SkillExecution exec) {
        executions.remove(exec);
    }

    // ── 메인 틱 ───────────────────────────────────────────────────────────

    private void onTick() {
        // 모든 JEntity 틱 처리 (주력 회복, 쿨다운, 상태이상 등)
        for (var je : JEntityManager.instance.all()) {
            je.onTick();
        }

        executions.removeIf(SkillExecution::isDone);

        // ── Phase 1: 즉시 실행 (로직·에너지·데미지 — 예산 제한 없음) ────────
        if (!executions.isEmpty()) {
            List<SkillExecution> snapshot = new ArrayList<>(executions);
            for (SkillExecution exec : snapshot) {
                exec.tick();
            }

            // ENDED 된 스킬: executions 에서 제거하고 draining 으로 이동
            // (pendingBreaks 가 남아있으면 계속 소화)
            executions.removeIf(exec -> {
                if (exec.isDone()) { draining.add(exec); return true; }
                return false;
            });
        }

        // ── Phase 2a: WFQ 활성 스킬 블록 파괴 ────────────────────────────────
        if (!executions.isEmpty()) {
            executions.sort(Comparator.comparingInt(SkillExecution::getPriority));

            int remaining = budgetPerTick;
            int i = 0;
            while (i < executions.size() && remaining > 0) {
                int priority = executions.get(i).getPriority();
                int groupEnd = i;
                while (groupEnd < executions.size()
                        && executions.get(groupEnd).getPriority() == priority) groupEnd++;

                int groupSize = groupEnd - i;
                int perExec = Math.max(1, remaining / groupSize);

                for (int j = i; j < groupEnd && remaining > 0; j++) {
                    int allowed = Math.min(perExec, remaining);
                    remaining -= executions.get(j).flushBlocks(allowed);
                }
                i = groupEnd;
            }
        }

        // ── Phase 2b: draining 큐 소화 (ENDED 스킬, 독립 예산) ───────────────
        // pendingBreaks 가 비면 (flushed == 0 with positive budget) 자동 제거.
        if (!draining.isEmpty()) {
            int[] drainRemaining = {drainBudgetPerTick};
            draining.removeIf(exec -> {
                if (drainRemaining[0] <= 0) return false;
                int flushed = exec.flushBlocks(drainRemaining[0]);
                drainRemaining[0] -= flushed;
                // flushed == 0 이고 양의 예산이었으면 큐가 비었으므로 제거
                return flushed == 0;
            });
        }
    }

    // ── 설정 ──────────────────────────────────────────────────────────────

    public void setBudgetPerTick(int budget) { this.budgetPerTick = budget; }
    public int getBudgetPerTick() { return budgetPerTick; }
    public void setDrainBudgetPerTick(int budget) { this.drainBudgetPerTick = budget; }
    public int getDrainBudgetPerTick() { return drainBudgetPerTick; }
}
