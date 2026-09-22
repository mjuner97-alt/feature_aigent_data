package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置种子数据：需求方给的口语化词清单原样入库（dimension_alias 表首次创建时灌入）。
 *
 * <p>跨维度同词的处置（docs/dimension-alias-config-plan.md §3.3）：
 * <ul>
 *   <li>军队：TEAM 行配触发词"军队组"（完整词），裸"军队"时 TEAM 行不参与 → 唯一落产品线；</li>
 *   <li>GBC / 财政 / 国库 / 个贷前端：暂无触发词，按 AliasResolver 规则 c/d 兜底
 *       （d 序为 PRODUCT_LINE &gt; TEAM &gt; APPLICATION，2026/09/21 拍板）；业务后续可自行
 *       加触发词行覆盖（见方案 §10）。</li>
 * </ul>
 */
final class DimensionAliasSeed {

    private DimensionAliasSeed() {}

    static List<DimensionAlias> builtin() {
        List<DimensionAlias> rows = new ArrayList<>();
        // 小组（32）
        team(rows, "GBC", "杭州服务支持部GBC场景创新组");
        team(rows, "GBC场景创新组", "杭州服务支持部GBC场景创新组");
        team(rows, "GPC组", "杭州二部GPC应用开发组");
        team(rows, "财政", "杭州服务支持部代理财政业务开发组");
        team(rows, "军队", "特种业务组", "军队组");
        team(rows, "车贷组", "个贷汽车场景建设组");
        team(rows, "创新消费组", "个贷消费场景组");
        team(rows, "代理财政开发组", "杭州服务支持部代理财政业务开发组");
        team(rows, "对公组", "杭州二部金融市场对公组");
        team(rows, "分行", "杭州服务支持部分行平台服务创新组");
        team(rows, "分行平台服务创新组", "杭州服务支持部分行平台服务创新组");
        team(rows, "个贷前端", "个贷对客渠道组");
        team(rows, "个贷渠道组", "个贷对客渠道组");
        team(rows, "个贷消费组", "个贷消费场景组");
        team(rows, "个贷住房组", "个贷住房场景建设组");
        team(rows, "个人组", "杭州二部金融市场个人组");
        team(rows, "交易报价组", "杭州二部金融市场交易报价组");
        team(rows, "境外对客组", "杭州二部金融市场境外对客组");
        team(rows, "境外组", "杭州二部金融市场境外对客组");
        team(rows, "量化交易组", "杭州二部量化交易组");
        team(rows, "量化组", "杭州二部量化交易组");
        team(rows, "平台组", "杭州二部FMBM应用平台组");
        team(rows, "汽车贷", "个贷汽车场景建设组");
        team(rows, "渠道组", "个贷对客渠道组");
        team(rows, "生态触客", "杭州服务支持部生态触客创新组");
        team(rows, "生态触客创新组", "杭州服务支持部生态触客创新组");
        team(rows, "同业", "同业客户组");
        team(rows, "投融资组", "杭州二部FMBM投融资组");
        team(rows, "线上化组", "杭州五部普惠大文章线上化组");
        team(rows, "应用平台组", "杭州二部FMBM应用平台组");
        team(rows, "中台组", "杭州二部金融市场中台组");
        // 业务确认（2026/09/21）：标准名"杭州二部FMBM资金"是完整专名，非截断
        team(rows, "资金交易组", "杭州二部FMBM资金");

        // 产品线（24；风险组两行 = 一对多）
        productLine(rows, "GBC", "民生政务");
        productLine(rows, "GMO", "金融市场后台运营项目");
        productLine(rows, "财政", "代理财政");
        productLine(rows, "对客", "金融市场对客交易");
        productLine(rows, "法贷", "法人信贷产品线");
        productLine(rows, "风险组", "全球市场风险管理应用");
        productLine(rows, "风险组", "金融产品定价与估值系统");
        productLine(rows, "个贷", "个人信贷产品线");
        productLine(rows, "国库", "代理国库");
        productLine(rows, "交易管理", "金融市场交易管理");
        productLine(rows, "交易下单", "金融市场交易下单");
        productLine(rows, "缴费", "缴费产品线");
        productLine(rows, "军队", "企业资金管理系统");
        productLine(rows, "开放银行", "生态金融管理");
        productLine(rows, "快捷", "快捷支付系统");
        productLine(rows, "快捷产品线", "快捷支付系统");
        productLine(rows, "快捷支付产品线", "快捷支付系统");
        productLine(rows, "量化", "量化投资及交易");
        productLine(rows, "票据", "票据产品线");
        productLine(rows, "普惠", "普惠金融");
        productLine(rows, "投研", "金融产品投资研究");
        productLine(rows, "询价", "金融市场内部询价及交易");
        productLine(rows, "养老金", "养老保险全国统筹基金管理系统");
        productLine(rows, "银企", "银企产品线");
        productLine(rows, "银企团队", "银企产品线");

        // 应用（11；三农政法、国库各两行 = 一对多）
        application(rows, "智慧三农", "FS-LFS-FARM");
        application(rows, "智慧政法", "FS-GBCP-EPL");
        application(rows, "三农政法", "FS-LFS-FARM");
        application(rows, "三农政法", "FS-GBCP-EPL");
        application(rows, "社保", "F-ASSP");
        application(rows, "国库", "F-TIPS");
        application(rows, "国库", "F-TIPS-CTBS");
        application(rows, "银证", "F-CBST");
        application(rows, "银商", "F-CBMT");
        application(rows, "银期", "F-CBFT");
        application(rows, "个贷前端", "F-WAPB-LOAN");
        application(rows, "银银", "F-BBC");
        return rows;
    }

    private static void team(List<DimensionAlias> rows, String alias, String standardName) {
        team(rows, alias, standardName, null, null);
    }

    private static void team(List<DimensionAlias> rows, String alias, String standardName, String trigger) {
        team(rows, alias, standardName, trigger, null);
    }

    private static void team(List<DimensionAlias> rows, String alias, String standardName,
                             String trigger, String remark) {
        rows.add(new DimensionAlias(null, PeerDimensionType.TEAM, alias, standardName, trigger, true, remark));
    }

    private static void productLine(List<DimensionAlias> rows, String alias, String standardName) {
        rows.add(new DimensionAlias(null, PeerDimensionType.PRODUCT_LINE, alias, standardName, null, true, null));
    }

    private static void application(List<DimensionAlias> rows, String alias, String standardName) {
        rows.add(new DimensionAlias(null, PeerDimensionType.APPLICATION, alias, standardName, null, true, null));
    }
}
