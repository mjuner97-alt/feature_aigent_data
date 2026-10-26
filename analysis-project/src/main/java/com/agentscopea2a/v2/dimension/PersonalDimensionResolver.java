/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.dimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "我X" 口语维度解析（人员台账维度方案 §3）：代词结构代码正则（不进 dimension_key_config，
 * 值随用户身份变化），凭统一认证号查台账 {@link PersonLedgerRepository} 解析归属。
 *
 * <p>产物供两条路径消费：直接执行路径在 {@code DirectSkillExecutor.plan} 做 extraction 级
 * enrich（主场景，§3.3）；LLM 回退路径在 DimensionStateMiddleware 写 DimensionState（§3.3b）。
 *
 * <p>降级契约（§3.5）：userId 空 / 台账查无此人 / 查询失败 → 命中标志保留但 ledger=null，
 * 调用方不注入任何维度。
 */
public class PersonalDimensionResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonalDimensionResolver.class);

    // 我部 / 我们部门 / 我的部门 / 本部门 / 我所在部门
    private static final Pattern MY_DEPT =
            Pattern.compile("我部|我们部门|我的部门|本部门|我所在部门");
    // 我的组 / 我们组 / 我的团队 / 我们团队 / 我的小组 / 我的统计组
    private static final Pattern MY_GROUP =
            Pattern.compile("我的组|我们组|我的团队|我们团队|我的小组|我的统计组");
    private static final Pattern MY_APP = Pattern.compile("我的应用(群组)?");
    private static final Pattern MY_PRODUCT = Pattern.compile("我的(产品线|条线|业务线)");
    // 裸"我"（§3.6 误伤约束）：前面非中文、后面非中文且不吃 我们/的/组/们 ——
    // "我的部门/我们组" 等复合词由具体维度正则承接；裸"我们"不触发任何注入
    private static final Pattern MYSELF = Pattern.compile("(?<![一-龥])我(个人|本人)?(?![一-龥组们的])");

    private final PersonLedgerRepository ledgerRepository;

    public PersonalDimensionResolver(PersonLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /** 解析结果：命中标志 + 台账归属（查无此人/userId 空时为 null，§3.5）。 */
    public record PersonalResolution(
            boolean myDept, boolean myGroup, boolean myApp, boolean myProductLine, boolean myself,
            PersonLedgerRepository.PersonLedgerRow ledger) {

        public boolean anyHit() {
            return myDept || myGroup || myApp || myProductLine || myself;
        }
    }

    /** resolve 的空结果常量（问题未命中任何口语词）。 */
    public static PersonalResolution none() {
        return new PersonalResolution(false, false, false, false, false, null);
    }

    /**
     * 解析问题里的"我X"口语词并查台账。命中具体维度（我部/我组/我的应用/我的产品线）
     * 时不再触发 MYSELF（§3.6：先查具体维度后查自身）。
     */
    public PersonalResolution resolve(String question, String userId) {
        if (question == null || question.isBlank()) {
            return none();
        }
        boolean myDept = MY_DEPT.matcher(question).find();
        boolean myGroup = MY_GROUP.matcher(question).find();
        boolean myApp = MY_APP.matcher(question).find();
        boolean myProduct = MY_PRODUCT.matcher(question).find();
        boolean myself = !myDept && !myGroup && !myApp && !myProduct
                && MYSELF.matcher(question).find();
        if (!myDept && !myGroup && !myApp && !myProduct && !myself) {
            return none();
        }
        PersonLedgerRepository.PersonLedgerRow ledger = null;
        if (userId == null || userId.isBlank()) {
            log.debug("PersonalDimensionResolver: 口语命中但 userId 为空, 不注入");
        } else {
            ledger = ledgerRepository.byUserId(userId);
            if (ledger == null) {
                log.info("PersonalDimensionResolver: userId={} 台账查无此人/查询失败, 不注入", userId);
            }
        }
        return new PersonalResolution(myDept, myGroup, myApp, myProduct, myself, ledger);
    }

    /**
     * §4.1 人名字典扫描：屏蔽文本（{@code DimensionStateManager.maskForPersonScan}）上
     * 穷举 2~4 字窗口（步进 1，最长优先），精确命中姓名字典即为姓名候选。
     * 字典为空（台账不可用）→ 整体关闭返回空。
     */
    public List<String> scanNames(String maskedQuestion) {
        Set<String> dictionary = ledgerRepository.nameDictionary();
        if (maskedQuestion == null || maskedQuestion.isBlank() || dictionary.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int n = maskedQuestion.length();
        for (int i = 0; i < n - 1; i++) {
            int maxLen = Math.min(4, n - i);
            for (int len = maxLen; len >= 2; len--) {
                String window = maskedQuestion.substring(i, i + len);
                if (dictionary.contains(window)) {
                    if (!out.contains(window)) {
                        out.add(window);
                    }
                    i += len - 1; // 最长命中消费整段，窗口越过
                    break;
                }
            }
        }
        return out;
    }

    /**
     * §4.2 按姓名解析身份（Q2 + 统一认证号去重）：单一身份 → 直填 perNo/perName；
     * 多个身份（重名）→ 调用方反问；空（字典/台账不一致或查询失败）→ 跳过。
     */
    public List<PersonLedgerRepository.PersonIdentity> resolveName(String name) {
        return ledgerRepository.byName(name);
    }
}
