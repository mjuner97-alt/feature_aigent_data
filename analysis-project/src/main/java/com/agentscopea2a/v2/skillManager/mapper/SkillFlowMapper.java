package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.SkillFlow;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNode;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowTrigger;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能流程持久化入口。
 *
 * <p>这里同时维护两类数据：</p>
 * <ul>
 *     <li>流程定义：流程、节点、依赖指标和触发词等可配置内容；</li>
 *     <li>流程运行态：流程实例、节点实例、执行尝试、worker 租约和通知记录。</li>
 * </ul>
 *
 * <p>涉及抢占、租约或状态流转的方法通常返回受影响行数。调用方应检查返回值，
 * {@code 0} 表示并发条件已发生变化，本次操作没有取得执行权或更新权。</p>
 */
@Mapper
public interface SkillFlowMapper {

    // ==================== 流程定义 ====================

    /** 新建流程:写入编码/名称/任务问题/汇总模板/排程星期/并发数/通知开关,回填自增 id。 */
    void insertFlow(SkillFlow flow);

    /** 按 id 查流程;已软删除(deleted_at 非空)的返回 null。 */
    SkillFlow selectFlowById(@Param("id") Long id);

    /** 按编码(全局唯一)查流程;已软删除的返回 null。 */
    SkillFlow selectFlowByCode(@Param("code") String code);

    /** 按名称精确查流程,用于重名校验;已软删除的返回 null。 */
    SkillFlow selectFlowByName(@Param("name") String name);

    /** 流程管理列表:按所有者/启用状态/名称编码关键字筛选,更新时间倒序,已删除不返回。 */
    List<SkillFlow> selectFlows(@Param("ownerUserId") String ownerUserId, @Param("enabled") Boolean enabled,
                                @Param("keyword") String keyword, @Param("createdBy") String createdBy);

    /**
     * 按指标反查启用中的流程(指标 → 节点 → 流程 三表 JOIN 去重)。
     * 推模式入口:指标就绪时由它找到该触发/唤醒的流程。
     */
    List<SkillFlow> selectEnabledFlowsByMetricId(@Param("metricId") Long metricId);

    /** 全量更新流程定义(按 id);软删除的流程不允许再改。 */
    void updateFlow(SkillFlow flow);

    /** 只切换启用/停用开关。 */
    void updateFlowEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);

    /** 软删除:置 deleted_at 并同时停用;历史执行记录保留,仅不可再被触发/修改。 */
    void softDeleteFlow(@Param("id") Long id);

    // ==================== 流程节点及依赖指标 ====================

    /**
     * 新建节点。更新流程定义时，业务层会先清理旧关联和旧节点，再按新定义重新写入。
     * node_key 流程内唯一,depends_on_json 存前置节点 key 数组构成 DAG,sort_order 决定执行顺序。
     */
    void insertNode(SkillFlowNode node);

    /** 查流程全部节点,按 sort_order(执行顺序)排列。 */
    List<SkillFlowNode> selectNodesByFlowId(@Param("flowId") Long flowId);

    /** 删除流程下所有节点的指标关联(编辑流程"先清后建"的第一步)。 */
    void deleteNodeMetricsByFlowId(@Param("flowId") Long flowId);

    /** 删除流程下所有节点(编辑流程"先清后建";指标关联须先于本操作删除)。 */
    void deleteNodesByFlowId(@Param("flowId") Long flowId);

    /** 给节点挂依赖指标(指标门控最小单元:节点执行前其全部依赖指标须 READY)。 */
    void insertNodeMetric(SkillFlowNodeMetric nodeMetric);

    /** 查节点依赖的全部指标 id,用于创建执行/门控重算时的就绪判断。 */
    List<Long> selectMetricIdsByNodeId(@Param("nodeId") Long nodeId);

    // ==================== 触发规则 ====================

    /**
     * 新建触发词。normalizedKeyword 是忽略输入格式差异后的全局唯一匹配键,
     * priority 决定同一条消息命中多个流程时的取舍。
     */
    void insertTrigger(SkillFlowTrigger trigger);

    /** 查流程全部触发词,按优先级降序(编辑页回显)。 */
    List<SkillFlowTrigger> selectTriggersByFlowId(@Param("flowId") Long flowId);

    /** 聊天入口用:按规范化关键词精确匹配,且所属流程须未删除。 */
    SkillFlowTrigger selectTriggerByNormalizedKeyword(@Param("normalizedKeyword") String normalizedKeyword);

    /** 删除流程的全部触发词(编辑流程"先清后建")。 */
    void deleteTriggersByFlowId(@Param("flowId") Long flowId);

    // ==================== 指标就绪状态 ====================

    /**
     * 删除同指标同数据日的就绪记录,与 {@link #insertMetricReadiness} 组成"删旧插新"幂等 upsert
     * (不用 ON CONFLICT 是为了兼容不同 openGauss 版本),合并同一指标同一天的重复上报。
     */
    void deleteMetricReadiness(@Param("metricId") Long metricId, @Param("dataDate") LocalDate dataDate);

    /** 插入新就绪记录(READY),expires_at 一般为次日零点,过期后按 EXPIRED 处理。 */
    void insertMetricReadiness(SkillMetricReadiness readiness);

    /** 查某指标某数据日的就绪记录;null 或 status != READY 即视为未就绪(门控卡住)。 */
    SkillMetricReadiness selectMetricReadiness(@Param("metricId") Long metricId,
                                               @Param("dataDate") LocalDate dataDate);

    /** 批量查一组指标在指定日期已 READY 的记录,用于就绪进度展示。 */
    List<SkillMetricReadiness> selectReadyMetrics(@Param("metricIds") List<Long> metricIds,
                                                  @Param("dataDate") LocalDate dataDate);

    // ==================== 流程实例与节点实例 ====================

    /**
     * 创建流程执行实例快照:保存触发信息及当时的流程配置(模板/并发/通知开关),
     * 后续改编排不影响在跑的执行。active_guard_key 唯一索引做幂等;
     * 并发冲突由服务层捕获 DuplicateKeyException 处理(SQL 不能用 ON DUPLICATE KEY UPDATE,
     * openGauss 不支持 RETURNING 与其并用,而 useGeneratedKeys 需要 RETURNING)。
     */
    int insertFlowExecution(SkillFlowExecution execution);

    /** 按 id 查执行实例,协调器执行节点/推进流程时的常规读取。 */
    SkillFlowExecution selectFlowExecutionById(@Param("id") Long id);

    /**
     * 查询流程实例并对数据库行加排他锁。
     * 必须在事务中使用，用于串行化同一流程实例的关键状态变更。
     */
    SkillFlowExecution selectFlowExecutionForUpdate(@Param("id") Long id);

    /** 为执行实例创建节点执行快照:模板/依赖/技能全部落快照,并初始化重试预算。 */
    void insertNodeExecution(SkillFlowNodeExecution execution);

    /** 查执行实例下全部节点(按创建顺序 = 配置执行顺序),推进/汇总/取消都以此为准。 */
    List<SkillFlowNodeExecution> selectNodeExecutions(@Param("flowExecutionId") Long flowExecutionId);

    // ==================== 运行态查询与状态维护 ====================

    /** 聊天入口候选触发词:触发词和所属流程都启用且流程未删除,优先级降序取最高命中。 */
    List<SkillFlowTrigger> selectEnabledTriggers();

    /**
     * 根据防重键(guard key)查执行实例,SQL 本身不区分状态:活跃期间复用防重复触发,
     * 终态记录是否还占用 guard 取决于服务层终态时是否释放 active_guard_key。
     */
    SkillFlowExecution selectActiveExecution(@Param("guardKey") String guardKey);

    /** 查某次会话最近关联的流程实例，优先返回仍持有防重键的活动实例;用于"直接回答"时取消最近执行。 */
    SkillFlowExecution selectLatestConversationExecution(@Param("userId") String userId, @Param("conversationId") String conversationId);

    /** 执行记录管理列表:按触发用户/状态筛选,最多返回最近 500 条。 */
    List<SkillFlowExecution> selectExecutions(@Param("status") String status, @Param("createdBy") String createdBy, @Param("userId") String userId);

    /** 扫描仍在等待依赖指标就绪(WAITING_METRICS)的流程实例,供门控重算和跨天超时兜底使用。 */
    List<SkillFlowExecution> selectWaitingExecutions();

    /** 全量更新执行实例:状态/guard/指标计数/汇总/报告/取消与起止时间,流程推进的唯一写入口。 */
    void updateExecution(SkillFlowExecution execution);

    /** 按 id 查单个节点执行实例,执行器读取认领后的节点详情。 */
    SkillFlowNodeExecution selectNodeExecution(@Param("id") Long id);

    // ==================== 节点调度、worker 租约与并发抢占 ====================

    /**
     * 找出当前可调度的节点，包括首次排队、等待重试，以及租约已过期且仍可重试的节点。
     * 查询同时受 nextRunAt、租约有效期和流程并行度限制；结果只是候选集，仍需调用
     * {@link #claimNode(Long, String, LocalDateTime, LocalDateTime)} 原子抢占后才能执行。
     * 每轮最多取 100 个,防止极端积压时单轮扫描刷库。
     */
    List<SkillFlowNodeExecution> selectRunnableNodes(@Param("now") LocalDateTime now);

    /** 查询租约已经过期且重试次数耗尽的节点(worker 已失联,不能重试),供兜底扫描判 FAILED 推进流程。 */
    List<SkillFlowNodeExecution> selectExpiredExhaustedNodes(@Param("now") LocalDateTime now);

    /**
     * 服务重启后回收其他 worker 遗留的 RUNNING 节点，并立即放回重试队列,
     * 无需等租约自然过期。
     *
     * @return 被回收的节点数量
     */
    int recoverAbandonedRunningNodes(@Param("owner") String owner, @Param("now") LocalDateTime now);

    /**
     * 原子抢占一个候选节点：切换为 RUNNING、增加尝试次数并写入 worker 租约,
     * 状态/租约条件不满足时更新 0 行(多实例并发下谁先 update 谁赢)。
     *
     * @return {@code 1} 表示抢占成功；{@code 0} 表示节点已被其他 worker 抢占或已不可运行
     */
    int claimNode(@Param("id") Long id, @Param("owner") String owner, @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    /** 统计租约仍有效的运行中节点，用于判断流程当前占用的并行度。 */
    int countActiveRunningNodes(@Param("flowExecutionId") Long flowExecutionId, @Param("now") LocalDateTime now);

    /**
     * 原子取得流程汇总权，仅允许 QUEUED 或 RUNNING 状态进入 SUMMARIZING,
     * 保证"全部节点终态后的汇总+通知"只执行一次。
     *
     * @return {@code 1} 表示取得汇总权；{@code 0} 表示流程已被其他线程推进
     */
    int claimExecutionForSummary(@Param("id") Long id);

    // ==================== 节点执行结果与尝试记录 ====================

    /** 将指定节点尚未结束的尝试标记为租约过期失败,节点判死/重新认领前调用,保持审计数据一致。 */
    int failRunningAttemptsForNode(@Param("nodeId") Long nodeId, @Param("now") LocalDateTime now);

    /** 全量更新节点执行实例(渲染问题/状态/尝试数/退避/租约/结果/错误/起止时间),节点状态流转的常规写入口。 */
    void updateNodeExecution(SkillFlowNodeExecution node);

    /**
     * 提交一次节点执行结果。只有节点仍为 RUNNING，且尝试次数、租约所有者均与预期一致时才更新。
     * 这些条件用于阻止租约过期的旧 worker 覆盖新 worker 已提交的结果。
     *
     * @return {@code 1} 表示提交成功；{@code 0} 表示当前 worker 已失去该节点的更新权,结果应丢弃
     */
    int completeNodeAttempt(@Param("node") SkillFlowNodeExecution node,
                            @Param("expectedAttempt") int expectedAttempt,
                            @Param("expectedLeaseOwner") String expectedLeaseOwner);

    /** 新建一次尝试审计记录(状态 RUNNING);attempt_no 须避开历史记录(与节点联合唯一)。 */
    void insertAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);

    /**
     * 查询节点审计记录中已存在的最大尝试号。
     * 手动重跑会把 attempt_count 归零重新获得重试预算,而 (节点, 尝试号) 有唯一索引,
     * 新尝试的编号必须避开历史记录。
     */
    int selectMaxAttemptNo(@Param("nodeId") Long nodeId);

    /**
     * 结束一次尝试，仅允许更新仍为 RUNNING 的记录，防止重复回调覆盖终态。
     *
     * @return 实际更新的记录数；{@code 0} 通常表示该尝试已经结束
     */
    int updateAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);

    /** 查节点的全部尝试审计记录,按 id(=尝试号)顺序,详情页重试轨迹展示用。 */
    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt> selectAttempts(@Param("nodeId") Long nodeId);

    /**
     * 汇总流程的有效执行耗时(秒):该执行下所有尝试审计记录的耗时之和(重跑多次会累加)。
     * 仍在 RUNNING 的尝试按当前时间计到此刻,因此执行中的任务也能得到已耗时。
     * 没有任何尝试记录(如还在等指标)返回 null。
     */
    Long selectActiveDurationSeconds(@Param("flowExecutionId") Long flowExecutionId);

    // ==================== 流程完成通知 ====================

    /** 查执行实例的通知投递记录(倒序),详情页展示通知状态/排查发送失败原因。 */
    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification> selectNotifications(@Param("executionId") Long executionId);

    /** 新建通知投递记录,delivery_key 全局唯一防重(初次完成通知为 flow:{executionId}:INITIAL)。 */
    void insertNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);

    /** 回写通知发送结果:状态/响应/错误/发送时间。 */
    void updateNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);
}
