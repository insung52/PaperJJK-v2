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
    private int taskId = -1;

    /** 틱당 총 작업 토큰 (config 에서 로드) */
    private int budgetPerTick = 80;

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

        if (executions.isEmpty()) return;

        // 완료된 스킬 제거
        executions.removeIf(SkillExecution::isDone);

        if (executions.isEmpty()) return;

        // 우선순위 정렬 (낮은 숫자 = 높은 우선순위)
        executions.sort(Comparator.comparingInt(SkillExecution::getPriority));

        // WFQ: 우선순위 그룹별로 예산 배분
        // 같은 우선순위 그룹 내에서는 균등 분배
        int remaining = budgetPerTick;
        int i = 0;
        while (i < executions.size() && remaining > 0) {
            // 같은 우선순위 그룹 찾기
            int priority = executions.get(i).getPriority();
            int groupEnd = i;
            while (groupEnd < executions.size()
                    && executions.get(groupEnd).getPriority() == priority) {
                groupEnd++;
            }
            int groupSize = groupEnd - i;
            int perExec = Math.max(1, remaining / groupSize);

            for (int j = i; j < groupEnd; j++) {
                executions.get(j).tick();
                remaining -= perExec;
            }
            i = groupEnd;
        }
    }

    // ── 설정 ──────────────────────────────────────────────────────────────

    public void setBudgetPerTick(int budget) { this.budgetPerTick = budget; }
    public int getBudgetPerTick() { return budgetPerTick; }
}
