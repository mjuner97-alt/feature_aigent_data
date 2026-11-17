package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonLedgerRow;

import java.util.List;
import java.util.Map;

/** 例会材料当前使用的演示人员目录，提供姓名、认证号及部门映射。 */
final class PptDemoPeople {
    private static final Map<String, PersonLedgerRow> PEOPLE = Map.of(
            "skill_demo_u001", person("skill_demo_u001", "张敏", "平台研发部"),
            "skill_demo_u003", person("skill_demo_u003", "王芳", "数据产品部"),
            "skill_demo_u002", person("skill_demo_u002", "李伟", "质量保障部"),
            "skill_demo_u004", person("skill_demo_u004", "张一", "平台研发部"),
            "skill_demo_u005", person("skill_demo_u005", "李二", "质量保障部"),
            "skill_demo_u006", person("skill_demo_u006", "王三", "数据产品部"));

    /** 保留演示人员目录的私有构造入口。 */
    private PptDemoPeople() {}

    /** 按用户标识查找当前演示人员目录。 */
    static PersonLedgerRow find(String userId) {
        return userId == null ? null : PEOPLE.get(userId.trim());
    }

    /** 返回按认证号排序的演示人员选项。 */
    static List<Map<String, String>> options() {
        return PEOPLE.values().stream()
                .sorted(java.util.Comparator.comparing(PersonLedgerRow::userId))
                .map(person -> Map.of("userId", person.userId(), "name", person.name(), "department", person.depts().get(0)))
                .toList();
    }

    /** 构造带姓名及部门的演示人员记录。 */
    private static PersonLedgerRow person(String id, String name, String department) {
        return new PersonLedgerRow(List.of(department), List.of(), List.of(), List.of(), name, id);
    }
}
