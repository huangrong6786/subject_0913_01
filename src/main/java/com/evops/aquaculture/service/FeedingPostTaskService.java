package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.FeedingPostTaskResult;
import com.evops.aquaculture.entity.TaskLock;
import com.evops.aquaculture.entity.TaskRun;
import com.evops.aquaculture.enums.TaskRunStatus;
import com.evops.aquaculture.enums.TaskTriggerSource;
import com.evops.aquaculture.mapper.TaskLockMapper;
import com.evops.aquaculture.mapper.TaskRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 投饵记录批量落账任务：定时调度与手工触发共用同一条执行路径。
 *
 * 防重复处理（修复“重复定时处理”缺陷）分三层：
 * 1. 任务锁 {@code t_task_lock}：单实例范围内定时线程与手工请求互斥；owner_token 标识持锁方，
 *    带租约 lease_expire_time，执行方异常退出后租约到期可被接管，不会永久阻塞。
 * 2. 业务周期台账 {@code t_task_run}：(task_name, period) 唯一。SUCCESS 为成功终态，重复触发短路；
 *    FAILED 与僵死 RUNNING 可接管重试，attempts 累加；活跃 RUNNING 跳过。
 * 3. 落账动作本身为逐条原子 CAS（posted=0 才置 1），即使台账/锁失效也不会重复落账。
 */
@Service
public class FeedingPostTaskService {

    private static final Logger log = LoggerFactory.getLogger(FeedingPostTaskService.class);

    public static final String TASK_NAME = "FEEDING_POST";
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TaskLockMapper taskLockMapper;
    private final TaskRunMapper taskRunMapper;
    private final FeedingRecordService feedingRecordService;

    /** 任务总开关：测试环境关闭，避免调度干扰。 */
    @Value("${evops.task.feeding-post.enabled:true}")
    private boolean enabled;

    /** 租约秒数：超过该时长未结束的持锁/执行视为异常退出，可被接管。 */
    @Value("${evops.task.feeding-post.lease-seconds:300}")
    private long leaseSeconds;

    public FeedingPostTaskService(TaskLockMapper taskLockMapper,
                                  TaskRunMapper taskRunMapper,
                                  FeedingRecordService feedingRecordService) {
        this.taskLockMapper = taskLockMapper;
        this.taskRunMapper = taskRunMapper;
        this.feedingRecordService = feedingRecordService;
    }

    /** 定时入口（Spring 6 段 cron，默认每天 02:00）；关闭时直接空转。 */
    @Scheduled(cron = "${evops.task.feeding-post.cron:0 0 2 * * *}")
    public void scheduledTick() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            runCycle(periodOf(now), TaskTriggerSource.SCHEDULED, now);
        } catch (Exception ex) {
            // 调度线程不能因单次异常被终止；僵死状态由租约接管兜底。
            log.error("投饵批量落账定时任务异常", ex);
        }
    }

    /** 手工触发入口：处理当天业务周期。 */
    public FeedingPostTaskResult triggerManual() {
        LocalDateTime now = LocalDateTime.now();
        return runCycle(periodOf(now), TaskTriggerSource.MANUAL, now);
    }

    /**
     * 执行一个业务周期。定时与手工共用，包内/测试可指定周期做并发回归。
     * 返回 SKIPPED 表示本周期无需/不允许本执行方处理；SUCCESS/FAILED 表示本次执行结局。
     */
    public FeedingPostTaskResult runCycle(String period, TaskTriggerSource source, LocalDateTime now) {
        // 快速短路：成功终态周期直接跳过，不抢锁、不产生任何副作用。
        TaskRun existing = findRun(period);
        if (existing != null && TaskRunStatus.SUCCESS.name().equals(existing.getStatus())) {
            return skipped(period, source, "业务周期 " + period + " 已成功处理，跳过重复执行", existing);
        }

        String token = UUID.randomUUID().toString();
        if (!acquireLock(token, now)) {
            return skipped(period, source, "任务正在其他执行方处理中（锁未过期），本次跳过", findRun(period));
        }

        try {
            ClaimOutcome claim = claimPeriod(period, source, now);
            if (!claim.acquired) {
                return skipped(period, source, claim.reason, claim.run);
            }
            TaskRun run = claim.run;

            int delta;
            try {
                // 逐条 CAS 自动提交：中途异常时已落账记录不回滚，重试只处理剩余记录。
                delta = doPostPending();
            } catch (Exception ex) {
                LocalDateTime failAt = LocalDateTime.now();
                taskRunMapper.markFailed(run.getId(), truncate(ex.getMessage()), failAt);
                log.warn("投饵批量落账失败，周期 {} 第 {} 次尝试，可重试: {}",
                        period, run.getAttempts(), ex.getMessage());
                return toResult(period, source, TaskRunStatus.FAILED,
                        taskRunMapper.selectById(run.getId()), "落账处理失败: " + ex.getMessage(), failAt);
            }

            LocalDateTime finishAt = LocalDateTime.now();
            taskRunMapper.markSuccess(run.getId(), delta, finishAt);
            TaskRun finished = taskRunMapper.selectById(run.getId());
            log.info("投饵批量落账成功，周期 {} 第 {} 次尝试，本次落账 {} 条，累计 {} 条",
                    period, finished.getAttempts(), delta, finished.getPostedCount());
            return toResult(period, source, TaskRunStatus.SUCCESS, finished,
                    "处理完成，本次落账 " + delta + " 条", finishAt);
        } finally {
            // 仅持锁方自身释放；SQL 异常也不能阻断释放，租约到期后仍可被接管。
            safeRelease(token);
        }
    }

    /** 业务执行 seam：回归测试可借助 Spy 注入失败。 */
    protected int doPostPending() {
        return feedingRecordService.postPending();
    }

    // ---------------- 任务锁 ----------------

    private boolean acquireLock(String token, LocalDateTime now) {
        LocalDateTime expire = now.plusSeconds(leaseSeconds);
        for (int i = 0; i < 2; i++) {
            TaskLock lock = taskLockMapper.selectOne(new LambdaQueryWrapper<TaskLock>()
                    .eq(TaskLock::getLockName, TASK_NAME));
            if (lock == null) {
                TaskLock fresh = new TaskLock();
                fresh.setLockName(TASK_NAME);
                fresh.setOwnerToken(token);
                fresh.setLockedAt(now);
                fresh.setLeaseExpireTime(expire);
                try {
                    taskLockMapper.insert(fresh);
                    return true;
                } catch (DuplicateKeyException dup) {
                    // 并发首建，重新竞争。
                    continue;
                }
            }
            if (lock.getLeaseExpireTime() != null && lock.getLeaseExpireTime().isAfter(now)) {
                return false;
            }
            // 租约过期：条件接管，并发接管只有一方 affected=1。
            return taskLockMapper.takeOverExpired(TASK_NAME, token, now, expire) == 1;
        }
        return false;
    }

    private void safeRelease(String token) {
        try {
            taskLockMapper.release(TASK_NAME, token);
        } catch (Exception ex) {
            log.warn("释放任务锁失败，将等待租约到期自动接管: {}", ex.getMessage());
        }
    }

    // ---------------- 周期台账 ----------------

    private ClaimOutcome claimPeriod(String period, TaskTriggerSource source, LocalDateTime now) {
        LocalDateTime staleBefore = now.minusSeconds(leaseSeconds);
        for (int i = 0; i < 2; i++) {
            TaskRun run = findRun(period);
            if (run == null) {
                TaskRun fresh = new TaskRun();
                fresh.setTaskName(TASK_NAME);
                fresh.setPeriod(period);
                fresh.setStatus(TaskRunStatus.RUNNING.name());
                fresh.setTriggerSource(source.name());
                fresh.setAttempts(1);
                fresh.setPostedCount(0);
                fresh.setStartedAt(now);
                try {
                    taskRunMapper.insert(fresh);
                    ClaimOutcome ok = new ClaimOutcome();
                    ok.acquired = true;
                    ok.run = fresh;
                    return ok;
                } catch (DuplicateKeyException dup) {
                    continue;
                }
            }

            if (TaskRunStatus.SUCCESS.name().equals(run.getStatus())) {
                return ClaimOutcome.skip("业务周期 " + period + " 已成功处理，跳过重复执行", run);
            }
            if (TaskRunStatus.RUNNING.name().equals(run.getStatus())
                    && run.getStartedAt() != null && run.getStartedAt().isAfter(staleBefore)) {
                return ClaimOutcome.skip("业务周期 " + period + " 有执行中的任务（未僵死），本次跳过", run);
            }
            // FAILED 或僵死 RUNNING：CAS 接管，SUCCESS 永远进不来此分支。
            int affected = taskRunMapper.claimRunning(run.getId(), source.name(), now, staleBefore);
            if (affected == 1) {
                ClaimOutcome ok = new ClaimOutcome();
                ok.acquired = true;
                ok.run = taskRunMapper.selectById(run.getId());
                return ok;
            }
            return ClaimOutcome.skip("业务周期 " + period + " 已被其他执行方接管重试", run);
        }
        return ClaimOutcome.skip("业务周期 " + period + " 抢占失败，本次跳过", findRun(period));
    }

    private TaskRun findRun(String period) {
        return taskRunMapper.selectOne(new LambdaQueryWrapper<TaskRun>()
                .eq(TaskRun::getTaskName, TASK_NAME)
                .eq(TaskRun::getPeriod, period));
    }

    // ---------------- 结果组装 ----------------

    private FeedingPostTaskResult skipped(String period, TaskTriggerSource source,
                                          String message, TaskRun run) {
        FeedingPostTaskResult result = FeedingPostTaskResult.skipped(
                TASK_NAME, period, source.name(), message);
        if (run != null) {
            result.setAttempts(run.getAttempts());
            result.setPostedCount(run.getPostedCount());
        }
        return result;
    }

    private FeedingPostTaskResult toResult(String period, TaskTriggerSource source,
                                           TaskRunStatus status, TaskRun run,
                                           String message, LocalDateTime finishedAt) {
        FeedingPostTaskResult result = new FeedingPostTaskResult();
        result.setTaskName(TASK_NAME);
        result.setPeriod(period);
        result.setStatus(status.name());
        result.setTriggerSource(source.name());
        result.setAttempts(run == null ? 0 : run.getAttempts());
        result.setPostedCount(run == null ? 0 : run.getPostedCount());
        result.setMessage(message);
        result.setFinishedAt(finishedAt);
        return result;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String periodOf(LocalDateTime now) {
        return now.toLocalDate().format(PERIOD_FORMATTER);
    }

    private static class ClaimOutcome {
        private boolean acquired;
        private TaskRun run;
        private String reason;

        static ClaimOutcome skip(String reason, TaskRun run) {
            ClaimOutcome outcome = new ClaimOutcome();
            outcome.acquired = false;
            outcome.reason = reason;
            outcome.run = run;
            return outcome;
        }
    }
}
