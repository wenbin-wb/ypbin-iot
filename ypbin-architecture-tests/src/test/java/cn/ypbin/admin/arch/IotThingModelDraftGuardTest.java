/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 源码级架构规则：<b>物模型的每个写入口必须带草稿校验</b>。
 *
 * <p><b>为什么要把这件事钉成门禁</b>：已发布的物模型不可变（§3.8），写守卫
 * （{@code requireProductDraft} / {@code requireDraftByServiceId}）是这条不变量的**唯一防线**——
 * 没有任何数据库约束、也没有校验注解能拦住「往已发布产品的物模型里插一个属性」。
 * 少写一行守卫不会报错、不会红任何用例，只会静默让已发布版本被改掉（历史数据与设备绑定
 * 的版本随之失真）。本仓踩过「写守卫是唯一防线却被漏在某个入口」的同类问题，故按教训四
 * （约定必须有门禁兜底）把它变成构建失败。</p>
 *
 * <p><b>规则口径</b>：</p>
 * <ol>
 *   <li>目标 = 实现 {@code IotThingModelService} 的那个类（按「implements IotThingModelService」
 *       在源码里定位，不写死路径，改名/搬家后规则仍然生效——但必须**恰好命中一个**类，否则转红）；</li>
 *   <li>「写入口」= 该类的 <b>public 或包级私有</b>方法（同包其它类可调用的最低可见性档）中，
 *       自身或其（同类的）被调用方**直接/传递地**发生数据库写入的那些方法
 *       （写调用按动词识别，见 {@link #WRITE_CALL}）；</li>
 *   <li>这些方法的方法体里必须出现守卫调用（{@link #GUARD_CALLS}）；</li>
 *   <li>守卫本身也被钉住：{@code requireProductDraft} 必须比对 {@code ModelStatus.DRAFT}，且**非草稿
 *       分支要真的以抛异常收口**（{@link #guardThrowsOnNonDraft} + {@link #thenBranchBlocksControlFlow}，
 *       判定是**文本启发式**而非可达性分析）。</li>
 * </ol>
 *
 * <p><b>口径是复核“咬”出来的（2026-09-25 外委复核，本类因此改过三处）</b>：第一版规则自述是
 * 「唯一防线」，却被三组变异静默放行——① 写入口用裸 {@code baseMapper.update(entity, wrapper)}
 * （MyBatis-Plus 最常规写法，动词白名单里没有 {@code update}）；② 守卫被弱化成「比对 DRAFT 但只
 * {@code log.warn}，另放一处不可达 {@code throw} 当装饰」；③ 新增**包级私有**写入口（无
 * {@code public}）。现已分别修掉：动词白名单补全 + 守卫必须「在非草稿分支内抛」+ 包级私有纳入
 * 写入口。这三条都有对应的自检用例，改动时不要退回去。</p>
 *
 * <p><b>已知边界（如实声明，避免虚假安心）</b>：</p>
 * <ul>
 *   <li>守卫判定是<b>文本启发式，不是可达性分析</b>：{@link #thenBranchBlocksControlFlow} 只检查
 *       「非草稿分支的**最后一条语句**是 throw（或 {@code throwXxx(…)}）」。「用更绕的方式把 throw
 *       伪装成收口语句、实际不可达」理论上仍能骗过——这类刻意绕过（而非顺手漏写）由 review 兜。
 *       该判据刻意**不**禁用日志：「先 log.warn 留痕、再 throw 拒绝」是合法且更好的写法
 *       （三轮复核 FPc 证明用「禁日志」的黑名单会把它误判）。</li>
 *   <li>只查 public / 包级私有 方法与类内调用图。<b>private</b> 辅助方法（如 {@code replaceTsl}）
 *       允许不自己校验——编译器保证它无法从别的类调用，只能由本类的入口进来，而入口必须带守卫。
 *       这也是刻意要求的形态：守卫写在入口，review 时一眼可见。</li>
 *   <li>写动词是**闭集**（{@link #WRITE_CALL}）：已覆盖「动词 + 任意大写后缀」（
 *       {@code deleteStartedBefore} / {@code insertIgnoringDuplicates} / {@code reviveAndBump} 这类本仓
 *       命名风格都命中），但**没有**收录 {@code replace}/{@code merge}（与 {@code String.replace}、
 *       {@code Map.merge} 同形，会让只读方法误报）、也没有 {@code insertSelective} 之外的 ORM 专属命名。
 *       新命名写 API 必须同步登记——「已知写入口清单」自检只能兜住「一个都没扫到」。
 *       （三轮复核建议进一步改成「Mapper 接收者 + 读白名单」的白名单制，属后续增强。）</li>
 *   <li>接收者不限 ⇒ {@code otherBean.save(x)} 也会被算成写入口（**不会**漏放）；代价是
 *       「调用别的 Bean 的只读方法但方法名像写」会有误报，需按实际语义调整白名单。</li>
 *   <li>新增守卫方法时必须同步登记进 {@link #GUARD_CALLS}，否则会被判成「缺守卫」（这是有意的：
 *       守卫名字必须收敛，不能人手一套）。</li>
 * </ul>
 *
 * <p><b>变异验证</b>（本类自带谓词自检；2026-09-25 外委复核另在真源码上做过 5 组）：
 * 删掉任一写入口的守卫调用、把守卫里的 {@code DRAFT} 改成 {@code PUBLISHED}、把守卫弱化成
 * 「只比对不抛」、新增用 {@code update(...)} 的无守卫入口、新增包级私有无守卫入口 —— 全部转红。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
class IotThingModelDraftGuardTest {

    /** 实现 {@code IotThingModelService} 的类的识别标记。 */
    private static final String IMPLEMENTATION_MARKER = "implements IotThingModelService";

    /** 草稿校验守卫的调用形态（新增守卫必须同步登记，见类注释的边界说明）。 */
    private static final List<String> GUARD_CALLS =
        List.of("requireProductDraft(", "requireDraftByServiceId(");

    /** 守卫必须真的比对**草稿态**（否则守卫退化为空方法/反向放行，整条不变量失效）。 */
    private static final Pattern DRAFT_COMPARISON = Pattern.compile("ModelStatus\\.DRAFT\\b");

    /** 类声明（抓类名与类体左花括号）。 */
    private static final Pattern CLASS_DECLARATION =
        Pattern.compile("\\bclass\\s+(\\w+)[^{]*\\{");

    /** 方法声明里「方法名」的位置：左括号前紧邻的标识符。 */
    private static final Pattern METHOD_NAME = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$");

    /** 方法声明里的可见性修饰符（取最后一个，即紧邻返回类型的那一个）。 */
    private static final Pattern VISIBILITY = Pattern.compile("(?<![\\w$])(public|private|protected)\\s");

    /**
     * 数据库写入动词（接收者不限：{@code save(...)} / {@code baseMapper.insert(...)} 都算）。
     *
     * <p>白名单必须覆盖 MyBatis-Plus 的<b>常规写法</b>：{@code update(entity, wrapper)}（复核变异 B3b
     * 用的就是它，第一版漏了它 ⇒ 规则静默放行）、{@code saveOrUpdate*}、以及 {@code deleteByXxx} /
     * {@code updateByXxx} 这类「动词+后缀」命名。新增写 API 命名时同步登记本常量。</p>
     */
    private static final Pattern WRITE_CALL = Pattern.compile(
        "\\.?\\b(?:save|update|remove|insert|delete|revive|bump|upsert|batchUpdate|updateBatch"
            + "|physicalDelete)(?:[A-Z]\\w*)?\\s*\\(");

    /** Java 关键字/修饰符：出现在「类型」位置说明这条匹配不是方法声明（构造器、调用点、控制语句）。 */
    private static final Set<String> NOT_A_TYPE = Set.of(
        "if", "for", "while", "switch", "return", "throw", "new", "catch", "do", "else", "case",
        "assert", "yield", "try", "instanceof", "super", "this", "class", "interface", "enum",
        "record", "public", "private", "protected", "static", "final", "abstract", "synchronized");

    @Test
    @DisplayName("★ 物模型每个写入口都必须先做草稿校验（守卫是已发布不可变的唯一防线）")
    void thingModelWriteMethodsMustCallDraftGuard() throws IOException {
        String source = SourceConventionTest.stripCommentsAndLiterals(
            Files.readString(implementationSource(), StandardCharsets.UTF_8));
        List<Method> methods = methods(source);
        List<Method> writeEntries = writeEntryMethods(methods);

        assertThat(writeEntries)
            .as("一个「会写库的 public/包级私有方法」都没扫到 ⇒ 本规则是空跑（教训八：0 违规可能是没跑到）")
            .isNotEmpty();
        assertThat(writeEntries).extracting(Method::name)
            .as("写入口的识别结果必须包含已知的这几个方法，否则说明提取逻辑已经漂移")
            .contains("createService", "updateService", "removeService", "createProperty",
                "removeProperty", "createCommand", "removeCommand", "createEvent", "removeEvent",
                "importTsl");

        List<String> violations = new ArrayList<>();
        for (Method method : writeEntries) {
            if (GUARD_CALLS.stream().noneMatch(method.body()::contains)) {
                violations.add("IotThingModelServiceImpl#" + method.name()
                    + " → 方法体会写库但没有草稿校验（缺 requireProductDraft/requireDraftByServiceId）");
            }
        }
        assertThat(violations)
            .as("写守卫是「已发布物模型不可变」的唯一防线：漏一个入口就会静默改掉已发布版本")
            .isEmpty();
    }

    @Test
    @DisplayName("★ 守卫本身必须真的比对草稿态（否则把守卫改成空方法即可绕过上一条规则）")
    void draftGuardMustActuallyCompareModelStatus() throws IOException {
        String source = SourceConventionTest.stripCommentsAndLiterals(
            Files.readString(implementationSource(), StandardCharsets.UTF_8));
        List<Method> guards = methods(source).stream()
            .filter(method -> method.name().equals("requireProductDraft"))
            .toList();

        assertThat(guards).as("找不到守卫方法 requireProductDraft ⇒ 本规则空跑").hasSize(1);
        Method guard = guards.getFirst();
        assertThat(guard.body()).as("守卫必须比对草稿态 ModelStatus.DRAFT（写成 PUBLISHED 等于把不变量反过来）")
            .containsPattern(DRAFT_COMPARISON);
        assertThat(guardThrowsOnNonDraft(guard.body()))
            .as("守卫必须在**同一条非草稿分支内**抛异常：只比对不抛（或把 throw 挪到别处当装饰）"
                + "都等于把不变量拆掉，而门禁必须咬得住这种形态（复核变异 B3c 实证）")
            .isTrue();
    }

    /**
     * 守卫的语义校验：在「比较 {@code ModelStatus.DRAFT} 的否定式 {@code if}」块内必须出现 {@code throw}。
     *
     * <p>为什么不能用 {@code contains("throw ")}：复核把守卫改成「比对 DRAFT 但只 {@code log.warn}，
     * 另放一处不可达的 {@code throw} 当装饰」，两条 {@code contains} 断言全过而门禁全绿——
     * 声明与实际防线不一致比没有门禁更危险。</p>
     *
     * @param guardBody 守卫方法体（已剥离注释与字面量）
     * @return 存在「非草稿 ⇒ 抛异常」的分支返回 {@code true}
     */
    static boolean guardThrowsOnNonDraft(String guardBody) {
        int cursor = 0;
        while (cursor < guardBody.length()) {
            int draft = guardBody.indexOf("ModelStatus.DRAFT", cursor);
            if (draft < 0) {
                return false;
            }
            // 两种合法形态：`if (!DRAFT…) { … }`（if 在 DRAFT 之前）与
            // `boolean isDraft = DRAFT…; if (!isDraft) { … }`（if 在 DRAFT 之后）。
            // 只看 before 会把后者判成假红（复核 N3 实证）。
            int ifStart = guardBody.lastIndexOf("if", draft);
            if (ifStart < 0) {
                ifStart = guardBody.indexOf("if", draft);
            }
            int open = guardBody.indexOf('{', Math.max(ifStart, draft));
            if (ifStart < 0 || open < 0) {
                return false;
            }
            int close = closingIndex(guardBody, open, '{', '}');
            if (close < 0) {
                return false;
            }
            String condition = guardBody.substring(ifStart, open);
            if (condition.contains("!") && thenBranchBlocksControlFlow(guardBody.substring(open, close + 1))) {
                return true;
            }
            cursor = close;
        }
        return false;
    }

    /**
     * 「then 分支确实拦下控制流」的**文本启发式**：分支内必须出现 {@code throw}（或名字以 {@code throw}
     * 开头的自建抛异常方法），且**不得**出现嵌套 {@code if} 或 {@code log.warn/info/debug}
     * ——后者是「实际放过、只留个装饰」的信号。
     *
     * <p>为什么需要它：复核 N2 把 {@code if (Boolean.FALSE) { throw … }} 塞进同一个非草稿分支、真实动作
     * 仍是 {@code log.warn}，就能骗过「块内出现 throw」这种判定。本启发式仍**不是**可达性分析
     * （见类注释的边界声明），但把「同分支内放装饰性 throw」这条最省事的绕过路径堵掉了。</p>
     *
     * @param thenBlock 含花括号的 then 分支文本
     * @return 分支以抛异常收口返回 {@code true}
     */
    static boolean thenBranchBlocksControlFlow(String thenBlock) {
        String inner = thenBlock.strip();
        if (inner.startsWith("{") && inner.endsWith("}")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        inner = inner.strip();
        while (inner.endsWith(";")) {
            inner = inner.substring(0, inner.length() - 1).strip();
        }
        // 取分支里**最后一条语句**：它必须就是抛异常（`throw …` 或名字以 throw 开头的自建方法）。
        // 反过来看「分支内是否出现过 throw」是不行的——把不可达 throw 塞进子结构（if/for/while）
        // 再配一句合法日志即可骗过（复核 N2/LEAK1）；而「先 log.warn 留痕再 throw」是**合法且更好**
        // 的写法，用黑名单禁日志会把它误判（复核 FPc）。收口判据一次解决两头。
        int boundary = Math.max(inner.lastIndexOf(';'),
            Math.max(inner.lastIndexOf('{'), inner.lastIndexOf('}')));
        String lastStatement = inner.substring(boundary + 1).strip().replaceAll("\\s+", " ");
        return lastStatement.startsWith("throw ")
            || Pattern.compile("^throw[A-Z]\\w*\\s*\\(").matcher(lastStatement).find();
    }

    @Test
    @DisplayName("自检：**直接调用规则所用的谓词**（规则被解除武装时必须被发现）")
    void ruleMustDetectViolationSemantically() {
        // 会写库且没有守卫 ⇒ 必须被判成违规
        List<Method> writesWithoutGuard = writeEntryMethods(methods(syntheticClass(
            "public Long createThing(ThingReq req) {\n"
                + "        save(new Thing());\n"
                + "        return 1L;\n"
                + "    }")));
        assertThat(writesWithoutGuard).extracting(Method::name).containsExactly("createThing");

        // 会写库且有守卫 ⇒ 不是违规（守卫存在即可，具体在哪个位置由人 review）
        List<Method> writesWithGuard = writeEntryMethods(methods(syntheticClass(
            "public void updateThing(Long id) {\n"
                + "        requireProductDraft(id);\n"
                + "        updateById(new Thing());\n"
                + "    }")));
        assertThat(writesWithGuard).extracting(Method::name).containsExactly("updateThing");
        assertThat(GUARD_CALLS.stream().anyMatch(writesWithGuard.getFirst().body()::contains)).isTrue();

        // 只读方法（哪怕名字像写）不得被判成写入口：只看方法体，不看名字
        List<Method> readOnly = writeEntryMethods(methods(syntheticClass(
            "public List<Thing> listThings(Long id) {\n"
                + "        return baseMapper.selectList(null);\n"
                + "    }")));
        assertThat(readOnly).as("读方法不能被误判为写入口（否则规则会到处误报，最后被人整体关掉）")
            .isEmpty();

        // 传递写：入口本身没有写调用，但调用了同类里会写库的方法 ⇒ 也算写入口
        String indirect = "    public void importThings(Long id) {\n"
            + "        replaceThings(id);\n"
            + "    }\n\n"
            + "    private void replaceThings(Long id) {\n"
            + "        baseMapper.deleteById(id);\n"
            + "    }\n";
        List<Method> transitive = writeEntryMethods(methods(syntheticClass(indirect)));
        assertThat(transitive).extracting(Method::name)
            .as("只认直接写调用会漏掉「入口委托给私有写方法」的形态（本仓 importTsl 就是这个形状）")
            .containsExactly("importThings");

        // 构造器/控制语句/调用点不得被当成方法声明
        assertThat(methods(syntheticClass("    if (condition) {\n        doSomething();\n    }\n")))
            .as("控制语句被误判成方法会让规则读到错误的「方法体」").isEmpty();
        assertThat(methods(syntheticClass("    public ThingServiceImpl(Thing thing) {\n"
            + "        this.thing = thing;\n"
            + "    }\n")))
            .as("构造器没有返回值，不能算写入口（否则规则会对每个构造器误报）").isEmpty();
        assertThat(methods(syntheticClass("    private static final Pattern P = Pattern.compile(\"x\");\n")))
            .as("字段声明不能被当成方法").isEmpty();
    }

    @Test
    @DisplayName("自检：**复核实证过的三类漏放**必须被咬住（写动词闭集 / 装饰性守卫 / 包级私有入口）")
    void ruleMustCatchTheEscapesFoundByReview() {
        // 漏放 ①：写入口用 MyBatis-Plus 最常规的裸 update(entity, wrapper)，第一版白名单里没有 update
        List<Method> bareUpdate = writeEntryMethods(methods(syntheticClass(
            "public void updateThing(Long id, ThingReq req) {\n"
                + "        baseMapper.update(new Thing(), Wrappers.<Thing>lambdaQuery().eq(Thing::getId, id));\n"
                + "    }")));
        assertThat(bareUpdate).extracting(Method::name)
            .as("裸 update( 也是写：不在白名单里就会静默放行（复核变异 B3b）")
            .containsExactly("updateThing");

        // 漏放 ②：守卫弱化成「比对 DRAFT 但只 log.warn」，另放不可达 throw 当装饰
        String decorativeGuard = "    private void requireProductDraft(Long productId) {\n"
            + "        Thing product = requireProduct(productId);\n"
            + "        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {\n"
            + "            log.warn(\"非草稿态，先放过\");\n"
            + "        }\n"
            + "        if (product.getId() == null) {\n"
            + "            throw new IllegalStateException(\"不可达\");\n"
            + "        }\n"
            + "    }\n";
        assertThat(guardThrowsOnNonDraft(methods(syntheticClass(decorativeGuard)).getFirst().body()))
            .as("只比对不抛（或 throw 在别的分支）= 不变量已被拆掉，门禁必须转红（复核变异 B3c）")
            .isFalse();

        // 第二轮复核 N2：装饰性 throw 挪进同一个「非草稿」分支内，真实动作仍是 log.warn
        String nestedDecoration = "    private void requireProductDraft(Long productId) {\n"
            + "        Thing product = requireProduct(productId);\n"
            + "        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {\n"
            + "            if (Boolean.FALSE) {\n"
            + "                throw new IllegalStateException(\"不可达装饰\");\n"
            + "            }\n"
            + "            log.warn(\"实际放过\");\n"
            + "        }\n"
            + "    }\n";
        assertThat(guardThrowsOnNonDraft(methods(syntheticClass(nestedDecoration)).getFirst().body()))
            .as("把装饰性 throw 塞进同一个非草稿分支、真实动作仍是放过 ⇒ 门禁必须转红（复核 N2）")
            .isFalse();

        // 第二轮复核 N3：把 DRAFT 比较提取成局部布尔变量是完全等价的合法重构，不得判成假红
        String extracted = "    private void requireProductDraft(Long productId) {\n"
            + "        Thing product = requireProduct(productId);\n"
            + "        boolean isDraft = ModelStatus.DRAFT.getCode().equals(product.getModelStatus());\n"
            + "        if (!isDraft) {\n"
            + "            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, \"先新建草稿\");\n"
            + "        }\n"
            + "    }\n";
        assertThat(guardThrowsOnNonDraft(methods(syntheticClass(extracted)).getFirst().body()))
            .as("提取局部布尔变量的等价重构不得被拒（复核 N3：假红会把正常重构逼向削弱规则）")
            .isTrue();

        // 第三轮复核 FPc：**合法**的「先 log.warn 留痕、再 throw 拒绝」不得被判红
        String logThenThrow = "    private void requireProductDraft(Long productId) {\n"
            + "        Thing product = requireProduct(productId);\n"
            + "        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {\n"
            + "            log.warn(\"非草稿态，将拒绝：productId={}\", productId);\n"
            + "            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, \"先新建草稿\");\n"
            + "        }\n"
            + "    }\n";
        assertThat(guardThrowsOnNonDraft(methods(syntheticClass(logThenThrow)).getFirst().body()))
            .as("「先 log.warn 再 throw」是合法（甚至更好）的守卫写法，不得被误判为放过（复核 FPc）")
            .isTrue();

        // 第三轮复核 LEAK1：「合法日志 + 子结构里的不可达 throw」不得被当成已拦下
        String loopDecoration = "    private void requireProductDraft(Long productId) {\n"
            + "        Thing product = requireProduct(productId);\n"
            + "        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {\n"
            + "            log.error(\"非草稿态，实际放过：productId={}\", productId);\n"
            + "            for (int i = 0; i < 0; i++) {\n"
            + "                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, \"不可达\");\n"
            + "            }\n"
            + "        }\n"
            + "    }\n";
        assertThat(guardThrowsOnNonDraft(methods(syntheticClass(loopDecoration)).getFirst().body()))
            .as("把不可达 throw 塞进子结构、真实动作仍是放过 ⇒ 必须转红（复核 LEAK1）")
            .isFalse();

        // 漏放 ③：包级私有（无修饰符）写入口——同包其它 *ServiceImpl 可直接调用
        List<Method> packagePrivate = writeEntryMethods(methods(syntheticClass(
            "void writeThing(Thing thing) {\n"
                + "        updateById(thing);\n"
                + "    }")));
        assertThat(packagePrivate).extracting(Method::name)
            .as("包级私有写入口对外可达（同包可调），只查 public 会漏（复核变异 B3d）")
            .containsExactly("writeThing");
    }

    /** 定位实现类源码文件（唯一命中，否则暴露而不是猜一个）。 */
    private static Path implementationSource() throws IOException {
        List<Path> hits = new ArrayList<>();
        for (Path file : SourceScan.mainSources()) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains(IMPLEMENTATION_MARKER)) {
                hits.add(file);
            }
        }
        assertThat(hits).as("必须恰好有一个类实现 %s（0 个 ⇒ 规则空跑；多个 ⇒ 需要补充口径）",
            IMPLEMENTATION_MARKER).hasSize(1);
        return hits.getFirst();
    }

    /**
     * 写入口：<b>public 或包级私有</b>、且直接写或（同类内传递地）调用到会写库的方法。
     *
     * <p>为什么把包级私有也算进来：包级私有方法在 {@code cn.ypbin.admin.iot.service.impl} 包内
     * 可被别的 {@code *ServiceImpl} 直接调用（本类已有包级私有的 {@code listServiceEntities}），
     * 因此它和 public 一样是「外部可达」的入口；只查 public 会静默放行这一类写入口
     * （复核变异 B3d 实证）。private 不在其列——编译器保证它无法跨类调用。</p>
     *
     * @param methods 类内全部方法
     * @return 写入口
     */
    static List<Method> writeEntryMethods(List<Method> methods) {
        Set<String> mutatingNames = mutatingNames(methods);
        List<Method> result = new ArrayList<>();
        for (Method method : methods) {
            if (method.isEntryPoint() && mutatingNames.contains(method.name())) {
                result.add(method);
            }
        }
        return result;
    }

    /** 传递闭包：先标记「直接写」的方法，再把「调用了会写的方法」也标上（迭代到不动点）。 */
    private static Set<String> mutatingNames(List<Method> methods) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : methods) {
            if (WRITE_CALL.matcher(method.body()).find()) {
                names.add(method.name());
            }
        }
        // 不动点迭代：调用图很小（单类），循环几次即收敛；上限防止意外死循环
        for (int round = 0; round < methods.size() + 1; round++) {
            int before = names.size();
            for (Method method : methods) {
                if (names.contains(method.name())) {
                    continue;
                }
                for (String callee : names) {
                    if (method.body().contains(callee + "(")) {
                        names.add(method.name());
                        break;
                    }
                }
            }
            if (names.size() == before) {
                break;
            }
        }
        return names;
    }

    /**
     * 抽取类体内的方法（可见性 + 方法名 + 方法体）。
     *
     * <p>输入必须是<b>已剥离注释与字符串</b>的源码：否则注释里的示例与字符串里的括号会让配对错位
     * （教训二十三：文本门禁必须作用在代码上）。</p>
     *
     * <p><b>为什么按花括号分段而不是按行正则</b>：先用正则（{@code ^ {4}类型 名字(}）定位声明，
     * 在真实源码上实测会因回溯与多行声明而**静默漏掉一部分私有方法**（本规则第一版就是这样：
     * {@code importTsl} 的传递写链因为漏了 {@code replaceTsl} 而没被识别成写入口，靠
     * 「已知方法清单」自检才发现）。改为「定位类体 → 按顶层 {@code ;}/{@code {} 切成员」后，
     * 提取结果与源码结构一一对应，且不再依赖缩进与换行。</p>
     *
     * @param source 源码（已剥离注释与字面量）
     * @return 方法清单
     */
    static List<Method> methods(String source) {
        Matcher classMatcher = CLASS_DECLARATION.matcher(source);
        if (!classMatcher.find()) {
            return List.of();
        }
        String className = classMatcher.group(1);
        int classOpen = classMatcher.end() - 1;
        int classClose = closingIndex(source, classOpen, '{', '}');
        if (classClose < 0) {
            return List.of();
        }
        return membersOf(source.substring(classOpen + 1, classClose), className);
    }

    /** 按「顶层 {@code ;} 或 {@code {…}}」切分成员，取其中带方法体、且声明部分以方法名结尾的那些。 */
    private static List<Method> membersOf(String classBody, String className) {
        List<Method> methods = new ArrayList<>();
        int index = 0;
        while (index < classBody.length()) {
            int semicolon = classBody.indexOf(';', index);
            int brace = classBody.indexOf('{', index);
            int end = classBody.indexOf('}', index);
            if (brace < 0 || (end >= 0 && end < brace)) {
                // 类体已经结束（防御：正常不会走到）
                break;
            }
            if (semicolon >= 0 && semicolon < brace) {
                // 字段声明或抽象方法：整体跳过（本规则只关心有方法体的方法）
                index = semicolon + 1;
                continue;
            }
            int bodyEnd = closingIndex(classBody, brace, '{', '}');
            if (bodyEnd < 0) {
                break;
            }
            Method method = toMethod(classBody.substring(index, brace),
                classBody.substring(brace, bodyEnd + 1), className);
            if (method != null) {
                methods.add(method);
            }
            index = bodyEnd + 1;
        }
        return methods;
    }

    /** 声明部分 → 方法（不是方法声明时返回 {@code null}）。 */
    private static Method toMethod(String declaration, String body, String className) {
        // 注解紧跟在**上一个成员**之后、本方法之前，会被并进本方法的「声明部分」
        // （`@Transactional(...)` 自身也带括号 ⇒ 不剥掉它就会被当成一个叫 Transactional 的方法，
        //  并把真正的方法体吞掉——本规则第一版就是这样把 12 个写入口全漏掉的）
        String stripped = stripLeadingAnnotations(declaration);
        int paren = stripped.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String beforeParen = stripped.substring(0, paren);
        Matcher nameMatcher = METHOD_NAME.matcher(beforeParen);
        if (!nameMatcher.find()) {
            // 字段初始化里的 lambda（`= () -> {`）等形态：名字位置不是标识符 ⇒ 不是方法
            return null;
        }
        String name = nameMatcher.group(1);
        if (NOT_A_TYPE.contains(name) || name.equals(className)) {
            // 控制语句残留 / 构造器：构造器没有返回值，也不承载「写入口校验」这条不变量
            return null;
        }
        String visibility = "";
        Matcher visibilityMatcher = VISIBILITY.matcher(beforeParen);
        while (visibilityMatcher.find()) {
            visibility = visibilityMatcher.group(1);
        }
        return new Method(visibility, name, body);
    }

    /**
     * 去掉声明开头的一串注解（{@code @Foo} / {@code @Foo(...)}，含嵌套括号与多行）。
     *
     * @param declaration 成员声明原文
     * @return 去掉前导注解后的声明
     */
    private static String stripLeadingAnnotations(String declaration) {
        String text = declaration.stripLeading();
        while (text.startsWith("@")) {
            int cursor = 1;
            while (cursor < text.length()
                && (Character.isJavaIdentifierPart(text.charAt(cursor)) || text.charAt(cursor) == '.')) {
                cursor++;
            }
            int bracket = cursor;
            while (bracket < text.length() && Character.isWhitespace(text.charAt(bracket))) {
                bracket++;
            }
            if (bracket < text.length() && text.charAt(bracket) == '(') {
                int close = closingIndex(text, bracket, '(', ')');
                cursor = close < 0 ? text.length() : close + 1;
            }
            text = text.substring(Math.min(cursor, text.length())).stripLeading();
        }
        return text;
    }

    /** 从 {@code open} 出发找到配对的 {@code close} 的下标（入参已剥离字面量，故只需数括号）。 */
    private static int closingIndex(String text, int open, char openChar, char closeChar) {
        if (open < 0 || open >= text.length() || text.charAt(open) != openChar) {
            return -1;
        }
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == openChar) {
                depth++;
            } else if (current == closeChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** 合成样例：包一个假类体，便于谓词自检直接调用生产同样的代码路径。 */
    private static String syntheticClass(String members) {
        return "class ThingServiceImpl {\n" + members + "}\n";
    }

    /**
     * 类内一个方法。
     *
     * @param visibility 可见性（{@code public}/{@code private}/{@code protected}/空串）
     * @param name       方法名
     * @param body       方法体（含花括号）
     */
    record Method(String visibility, String name, String body) {

        /**
         * 是否「外部可达的入口」：public 或包级私有（无修饰符）。
         *
         * @return public / 包级私有返回 {@code true}
         */
        boolean isEntryPoint() {
            return "public".equals(visibility) || visibility.isEmpty();
        }
    }
}
