package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.MizushiInnateTerritory;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.scheduler.WorkScheduler;

/**
 * 복마어주자(Malevolent Shrine) 영역전개.
 *
 * isOpen=false (일반):
 *   - 결계 재질: BEDROCK
 *   - 반경: 30블록
 *   - 구 블록 점진 생성 후 ACTIVE
 *   - 일반 엔티티 포획은 부모 클래스(DomainExpansion) 에서 처리
 *
 * isOpen=true (결없영):
 *   - 결계 없음, 현실에 전개
 *   - 매 틱 범위 내 LivingEntity에 해(Kai) 필중
 *   - 포획 없이 현실 전개 (DomainExpansion.captureAllEntitiesInRange 에서 isOpen 체크로 제외됨)
 */
public class MizushiDomainExpansion extends DomainExpansion {

    private static final double   BARRIER_LEVEL   = 10.0;
    private static final Material BARRIER_MAT     = Material.OBSIDIAN;
    private static final int      BLOCKS_PER_TICK = 200;

    private final DomainBlockBuilder builder;
    private final int openRange;

    private int syncTickCounter = 0;
    /** 결없영 ACTIVE 진입 후 틱 카운터 (사운드 타이밍용) */
    private int activeTick = 0;
    /** 주기적 전역 START 브로드캐스트 카운터 (100틱 = 5초마다) */
    private int globalSyncCounter = 0;

    /** 결없영 블럭 파괴 파도 (isOpen=true 시에만 사용) */
    private MizushiDestructionWave destructionWave = null;

    /** 결없영 활성 중 새로 생기는 블럭을 파괴하는 suppressor (isOpen=true 시에만 사용) */
    private MizushiBlockSuppressor blockSuppressor = null;

    public MizushiDomainExpansion(JEntity caster, InnateTerritory territory, boolean open, int range) {
        super(caster, territory, range, open, BARRIER_LEVEL);
        this.openRange = range;
        this.builder   = open ? null : new DomainBlockBuilder();
    }

    // ── DomainExpansion 구현 ──────────────────────────────────────────────

    @Override
    protected void onExpanding() {
        Location center = caster.entity.getLocation();

        if (++syncTickCounter % 5 == 0) {
            broadcastDomainVisualSync(center);
        }

        if (isOpen) {
            // 결없영: 블록 건설 없이 즉시 ACTIVE 전환 (첫 틱에만)
            if (syncTickCounter == 1) {
                domainPhase = DomainPhase.ACTIVE;
                // 결없영은 포획하지 않음 (captureAllEntitiesInRange의 isOpen 체크로 처리)
            }
        } else {
            boolean done = builder.buildTick(center, (int) getRange(), BARRIER_MAT, BLOCKS_PER_TICK);
            if (done) {
                domainPhase = DomainPhase.ACTIVE;
                captureAllEntitiesInRange(); // JEntity + 일반 엔티티 모두 포획
                broadcastDomainVisualActive(center);

                center.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE,
                        SoundCategory.MASTER, 3f, 0.5f);
            }
        }
    }

    @Override
    protected void onDomainActive() {
        innateTerritory.onActiveTick();

        // 결없영(isOpen): onExpanding이 1틱만 돌고 즉시 ACTIVE로 전환되므로
        // 여기서 2틱마다 sync 브로드캐스트 (파괴 파도의 현재 반경 전송)
        if (isOpen) {
            ++syncTickCounter;
            if (syncTickCounter % 2 == 0) {
                float waveRadius = destructionWave != null ? (float) destructionWave.getDestructionRadius() : 0f;
                JPacketSender.broadcastDomainVisualSync(
                    caster.entity.getLocation(), caster.uuid, waveRadius, DomainManager.BROADCAST_RANGE);
            }
            // 100틱(5초)마다 전역 START 브로드캐스트 → 늦게 들어온 플레이어 복구
            if (++globalSyncCounter >= 100) {
                globalSyncCounter = 0;
                float waveRadius = destructionWave != null ? (float) destructionWave.getDestructionRadius() : 0f;
                JPacketSender.broadcastDomainVisualStartGlobal(
                    caster.uuid, org.justheare.paperjjk.network.PacketIds.DomainType.MIZUSHI,
                    caster.entity.getLocation(), waveRadius, true);
            }
        }

        if (!(innateTerritory instanceof MizushiInnateTerritory mit)) return;

        if (isOpen) {
            // 결없영 사운드 타이밍 (중심 위치 기준)
            Location soundCenter = caster.entity.getLocation();
            if (soundCenter.getWorld() != null) {
                switch (activeTick++) {
                    case 0 -> {
                        // 시전 즉시
                        soundCenter.getWorld().playSound(soundCenter, Sound.ENTITY_ELDER_GUARDIAN_DEATH,    SoundCategory.MASTER, 5f, 0.5f);
                        soundCenter.getWorld().playSound(soundCenter, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.MASTER, 3f, 0.5f);
                    }
                    case 20 -> {
                        // 딜레이 중간
                        soundCenter.getWorld().playSound(soundCenter, Sound.ENTITY_BREEZE_SLIDE, SoundCategory.MASTER, 5f, 0.5f);
                    }
                    case 40 -> {
                        // 반경 확장 시작
                        soundCenter.getWorld().playSound(soundCenter, Sound.ENTITY_EVOKER_PREPARE_ATTACK,  SoundCategory.MASTER, 10f, 0.5f);
                        soundCenter.getWorld().playSound(soundCenter, Sound.ENTITY_EVOKER_PREPARE_SUMMON,  SoundCategory.MASTER, 10f, 0.5f);
                        soundCenter.getWorld().playSound(soundCenter, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, 5f,  0.5f);
                    }
                }
            }

            // 첫 ACTIVE 틱에 파괴 파도 시작 + 클라이언트에 START 패킷 전송
            if (destructionWave == null) {
                Location center = caster.entity.getLocation();
                // isOpen=true는 onExpanding이 1틱 만에 끝나 START 브로드캐스트가 안 보내지므로 여기서 전송
                broadcastDomainVisualSync(center, 0f);
                destructionWave = new MizushiDestructionWave(center, openRange, true);
                WorkScheduler.getInstance().register(destructionWave);
                blockSuppressor = new MizushiBlockSuppressor(center, openRange, MizushiDestructionWave.START_DELAY_TICKS);
                WorkScheduler.getInstance().register(blockSuppressor);
            }

            // 매 틱 범위 내 LivingEntity에 팔(Hachi) 필중
            Location center = caster.entity.getLocation();
            if (center.getWorld() != null) {
                center.getWorld().getNearbyEntities(center, openRange, openRange, openRange)
                        .stream()
                        .filter(e -> e instanceof org.bukkit.entity.LivingEntity
                                && !(e instanceof org.bukkit.entity.Player))
                        .forEach(e -> mit.applySureHitVanilla((org.bukkit.entity.LivingEntity) e));
            }
        } else {
            // 일반 영역전개: 포획된 바닐라 몹에게 팔(Hachi) 필중
            for (org.bukkit.entity.Entity e : capturedVanillaEntities) {
                if (e instanceof org.bukkit.entity.LivingEntity living && e.isValid()) {
                    mit.applySureHitVanilla(living);
                }
            }
        }
    }

    @Override
    protected void onClosing() {
        if (isOpen || builder == null) {
            // 결없영: 파괴 파도 중단 후 즉시 DONE
            if (destructionWave != null) {
                destructionWave.stop();
                destructionWave = null;
            }
            if (blockSuppressor != null) {
                blockSuppressor.stop();
                blockSuppressor = null;
            }
            domainPhase = DomainPhase.DONE;
            broadcastDomainVisualEndGlobal();
            return;
        }
        boolean done = builder.restoreTick(BLOCKS_PER_TICK);
        if (done) {
            domainPhase = DomainPhase.DONE;
            broadcastDomainVisualEndGlobal();
        }
    }

    @Override
    public boolean containsBarrierBlock(Location loc) {
        if (builder == null) return false;
        return builder.isBarrierBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** BlockBreak 이벤트에서 파손된 결계 블록의 색인 제거 */
    public void removeBarrierBlock(int bx, int by, int bz) {
        if (builder != null) builder.removeBarrierBlock(bx, by, bz);
    }

    // ── 패킷 브로드캐스트 ─────────────────────────────────────────────────

    private void broadcastDomainVisualSync(Location center) {
        broadcastDomainVisualSync(center, (float) getRange());
    }

    private void broadcastDomainVisualSync(Location center, float radius) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualStart(center,
                caster.uuid, PacketIds.DomainType.MIZUSHI,
                center, radius, isOpen,
                DomainManager.BROADCAST_RANGE);
    }

    private void broadcastDomainVisualActive(Location center) {
        broadcastDomainVisualSync(center);
    }

    private void broadcastDomainVisualEnd(Location center) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualEnd(center, caster.uuid, DomainManager.BROADCAST_RANGE);
    }

    /** 월드/거리 제한 없이 모든 플레이어에게 END 전송 (월드 전환 버그 방지). */
    private void broadcastDomainVisualEndGlobal() {
        JPacketSender.broadcastDomainVisualEndGlobal(caster.uuid);
    }
}
