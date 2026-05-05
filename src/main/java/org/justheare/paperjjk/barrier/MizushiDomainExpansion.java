package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.MizushiInnateTerritory;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.scheduler.WorkScheduler;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger("PaperJJK");

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

    /** 영역이 실제로 전개된 고정 중심 좌표 (ACTIVE 진입 시 캡처, 이후 불변). */
    private Location domainCenter = null;

    /** fuga 범위 판정에 사용할 고정 중심. ACTIVE 이전엔 null. */
    public Location getDomainCenter() { return domainCenter; }


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
            broadcastDomainVisualStart(center);
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
                broadcastDomainVisualStart(center);

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
                Location dc = domainCenter != null ? domainCenter : caster.entity.getLocation();
                if (JPacketSender.DOMAIN_DEBUG) LOG.info("[MizushiDomain] SYNC broadcast | tick=" + syncTickCounter
                    + " | waveRadius=" + waveRadius + " | destructionWave=" + (destructionWave != null)
                    + " | domainCenter=" + (domainCenter != null));
                JPacketSender.broadcastDomainVisualSync(
                    dc, caster.uuid, waveRadius, DomainManager.BROADCAST_RANGE,
                    PacketIds.DomainType.MIZUSHI, true);
            }
            // 100틱(5초)마다 전역 START 브로드캐스트 → 늦게 들어온 플레이어 복구 (거리 무제한)
            if (++globalSyncCounter >= 100) {
                globalSyncCounter = 0;
                float waveRadius = destructionWave != null ? (float) destructionWave.getDestructionRadius() : 0f;
                Location dc = domainCenter != null ? domainCenter : caster.entity.getLocation();
                if (JPacketSender.DOMAIN_DEBUG) LOG.info("[MizushiDomain] START(global) broadcast | waveRadius=" + waveRadius);
                JPacketSender.broadcastDomainVisualStartGlobal(
                    caster.uuid, PacketIds.DomainType.MIZUSHI, dc, waveRadius, true);
            }
        }

        if (!(innateTerritory instanceof MizushiInnateTerritory mit)) return;

        if (isOpen) {
            // 결없영 사운드 타이밍 (고정 중심 기준)
            Location soundCenter = domainCenter != null ? domainCenter : caster.entity.getLocation();
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
                domainCenter = center.clone(); // 고정 중심 저장
                // isOpen=true는 onExpanding이 1틱 만에 끝나 START 브로드캐스트가 안 보내지므로 여기서 전송
                // radius=0 → 클라이언트 충전 애니메이션 트리거
                if (JPacketSender.DOMAIN_DEBUG) LOG.info("[MizushiDomain] 첫 ACTIVE 틱 → START(radius=0) 전송 | center="
                    + String.format("(%.1f,%.1f,%.1f)", center.getX(), center.getY(), center.getZ())
                    + " | openRange=" + openRange);
                broadcastDomainVisualStart(center, 0f);
                destructionWave = new MizushiDestructionWave(center, openRange, true);
                WorkScheduler.getInstance().register(destructionWave);
                blockSuppressor = new MizushiBlockSuppressor(center, openRange, MizushiDestructionWave.START_DELAY_TICKS);
                WorkScheduler.getInstance().register(blockSuppressor);
            }

            // 파괴 파도의 현재 반경 내 엔티티에만 필중 적용 (fuga 충전 중에는 중단)
            // 결없영은 생득 영역 없이 현실에서 전개되므로 여기서 직접 스캔
            if (!fugaCharging && destructionWave != null) {
                double waveRadius = destructionWave.getDestructionRadius();
                // 파괴 파도가 아직 시작되지 않은 초반 40틱 동안은 필중 없음
                if (waveRadius > 0) {
                    Location center = domainCenter != null ? domainCenter : caster.entity.getLocation();
                    if (center.getWorld() != null) {
                        // JEntity 플레이어: CE 유무에 따라 Hachi/Kai 필중
                        for (JEntity je : JEntityManager.instance.all()) {
                            if (je == caster) continue;
                            if (je.technique != null && !je.technique.isDomainTarget()) continue;
                            if (!center.getWorld().equals(je.entity.getWorld())) continue;
                            if (je.entity.getLocation().distance(center) <= waveRadius) {
                                mit.applySureHit(je);
                            }
                        }
                        // 바닐라 몹: 팔(Hachi) 필중 (구 형태 보정 포함)
                        center.getWorld().getNearbyEntities(center, waveRadius, waveRadius, waveRadius)
                                .stream()
                                .filter(e -> e instanceof org.bukkit.entity.LivingEntity
                                        && !(e instanceof org.bukkit.entity.Player)
                                        && JEntityManager.instance.get(e.getUniqueId()) == null
                                        && e.getLocation().distance(center) <= waveRadius)
                                .forEach(e -> mit.applySureHitVanilla((org.bukkit.entity.LivingEntity) e));
                    }
                }
            }
        }
        // 일반 영역전개(isOpen=false)의 바닐라 몹 효과는
        // MizushiInnateTerritory.onActiveTick() 내 capturedVanillaEntities 순회로 처리
    }

    @Override
    protected void onClosing() {
        if (isOpen || builder == null) {
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

    // ── Fuga 폭발 처리 ────────────────────────────────────────────────────

    /**
     * fuga가 결없영 내부에 진입했을 때 호출.
     *
     * 처리 내용:
     *  1. 반경 내 엔티티에 반경^2.4 에 비례한 데미지 (한 번만)
     *  2. 클라이언트에 MIZUSHI_THERMOBARIC 패킷 브로드캐스트
     *  3. 5초(100틱) 후 영역전개 자동 종료
     *
     * @param impactPos fuga 충돌 위치 (미사용, 향후 블럭 파괴 등 확장 가능)
     */
    public void triggerFugaExplosion(Location impactPos) {
        if (domainPhase == DomainPhase.CLOSING || domainPhase == DomainPhase.DONE) return;

        Location center = domainCenter != null ? domainCenter : caster.entity.getLocation();
        float radius = (float) getRange();

        // 1. 반경 내 엔티티 데미지 — radius^2.4 에 비례 (분진 부피에 비례)
        double damage = Math.pow(radius, 2.4) * 0.1;
        if (center.getWorld() != null) {
            for (LivingEntity le : center.getWorld().getNearbyLivingEntities(center, radius)) {
                if (le == caster.entity) continue;
                var target = org.justheare.paperjjk.network.JEntityManager.instance != null
                        ? org.justheare.paperjjk.network.JEntityManager.instance.get(le.getUniqueId())
                        : null;
                if (target != null) {
                    target.receiveDamage(DamageInfo.domainSureHit(caster, damage, "mizushi_fuga_explosion"));
                } else {
                    org.justheare.paperjjk.network.JEntityManager.skillDamageInProgress.add(le.getUniqueId());
                    try {
                        le.damage(DamageInfo.outputToDamage(damage), caster.getLivingEntity());
                    } finally {
                        org.justheare.paperjjk.network.JEntityManager.skillDamageInProgress.remove(le.getUniqueId());
                    }
                }
            }
        }

        // 2. 클라이언트 열압력탄 효과 트리거
        double broadcastRange = radius * 2.0 + 128;
        JPacketSender.broadcastMizushiThermobaric(center, radius, broadcastRange);

        // 3. 경계(반경+1) 지상 블럭 폭발+화염 이펙트
        WorkScheduler.getInstance().register(new MizushiSurfaceExplosion(center, (int) radius, caster.entity));

        // 4. 즉시 영역 종료 (postprocessing + 블럭 파괴 즉시 OFF)
        collapse();
    }

    // ── 블럭 파괴 일시 중단 (fuga 충전 중) ───────────────────────────────────

    /** fuga 충전 중 여부 — true이면 Hachi 필중 데미지 + 블럭 파괴 중단. */
    private boolean fugaCharging = false;

    /** fuga 충전 시작 시 블럭 파괴 파도 + 서프레서 + Hachi 필중을 일시 중단. */
    public void pauseBlockDestruction() {
        fugaCharging = true;
        if (destructionWave  != null) destructionWave.pause();
        if (blockSuppressor  != null) blockSuppressor.pause();
    }

    /** fuga 충전 종료(미스/만료) 시 블럭 파괴 파도 + 서프레서 + Hachi 필중을 재개. */
    public void resumeBlockDestruction() {
        fugaCharging = false;
        if (destructionWave  != null) destructionWave.resume();
        if (blockSuppressor  != null) blockSuppressor.resume();
    }

    // ── 패킷 브로드캐스트 ─────────────────────────────────────────────────

    /** 현재 반경(getRange())으로 START 패킷 브로드캐스트. */
    private void broadcastDomainVisualStart(Location center) {
        broadcastDomainVisualStart(center, (float) getRange());
    }

    /** 지정 반경으로 START 패킷 브로드캐스트 (DomainVisualAction.START 전송). */
    private void broadcastDomainVisualStart(Location center, float radius) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualStart(center,
                caster.uuid, PacketIds.DomainType.MIZUSHI,
                center, radius, isOpen,
                DomainManager.BROADCAST_RANGE);
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
