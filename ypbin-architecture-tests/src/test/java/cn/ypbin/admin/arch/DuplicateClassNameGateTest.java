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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 门禁：{@code cn.ypbin.admin.*} 下**不得出现**与 {@code cn.ypbin.iot.*} / {@code cn.ypbin.starter.*}
 * **同名**（类简单名相同）的类。
 *
 * <p><b>为什么需要它（issue #295）</b>：本仓把通用能力持续下沉到 {@code ypbin-starter} /
 * {@code ypbin-iot-*}，但**下沉只做一半**的形态反复出现——业务仓里留着同名旧副本，生产路径已切到
 * starter、旧副本却还在（可编辑、可被同包测试静默解析）。典型两例：
 * {@code access/link/ModbusRegisterDecoder}（生产已 import starter 版，{@code ModbusStringDecodeTest}
 * 因**无 import** 解析到同包旧类 ⇒ 测试绿不代表生产对，属「测试与生产不同源」的假绿）、
 * {@code iot/availability/AvailabilityCalculator} 与 {@code OutageDetector}（下沉已半年未删，
 * 同一口径存在两个可编辑副本，改一处另一处静默失效）。两者都**没有任何编译期告警**——
 * 只能靠机器门禁把「忘记删副本」变成构建失败。</p>
 *
 * <p><b>判定口径</b>：本仓主源码里声明的**顶层类型简单名**（文件名 + 源码里顶格声明的
 * class/interface/enum/record/annotation）与「本仓声明版本对应的 starter/iot 制品里的顶层类简单名」
 * 取交集；交集非空即违规。<b>只比简单名、不比对内容</b>——同名本身就是歧义源（栈帧、IDE 跳转、
 * 同包测试的解析优先级都会被搅乱），而且「内容相同 ⇒ 副本」「内容不同 ⇒ 变体」两种情形都应当
 * 归一到 starter 唯一实现上。</p>
 *
 * <p><b>为什么扫 {@code ~/.m2} 而不是测试类路径</b>：本门禁模块（{@code ypbin-architecture-tests}）
 * 的测试类路径**不含** {@code ypbin-starter-iot} / {@code ypbin-iot-protocol-modbus}（它们只被
 * {@code ypbin-iot} / {@code ypbin-access} 依赖），用测试类路径会把「最该守的两个制品」漏掉。
 * 故按**本仓 pom 声明的版本**去本地仓库找制品：只认 {@code ypbin-starter.version} 与
 * {@code ypbin-iot.version} 这两个声明值，**不扫其它版本**（{@code ~/.m2} 里常有历史版本，混进来会
 * 制造与当前基线无关的假红）。</p>
 *
 * <p><b>防「空跑绿」</b>：① 解析到的制品数量、类数量都有下界；② 三个**锚点类**必须存在
 * （{@code starter-iot} / {@code starter-sign} / {@code iot-protocol-modbus} 各一个），
 * 证明扫描确实覆盖了目标制品而不是「找不到 jar 就当没冲突」；③ 本地仓库缺失锚点制品时
 * **显式失败**并给出「先构建/安装依赖」的指令（禁静默降级）。</p>
 *
 * <p><b>已知同名豁免</b>（必须写清理由，且必须**真的命中**一次冲突，否则本门禁转红）：
 * 见 {@link #ALLOWED_SIMPLE_NAMES}。豁免清单不是「放宽规则」——它把「同名但确实不是副本」的少数
 * 情形钉在明面上，任何新出现的同名仍会转红。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class DuplicateClassNameGateTest {

    /** 本仓主源码的包前缀（被检查的一侧）。 */
    private static final String REPO_PACKAGE_PREFIX = "cn/ypbin/admin/";

    /** 下游制品（starter / iot）的包前缀（提供「官方实现」的一侧）。 */
    private static final List<String> VENDOR_PACKAGE_PREFIXES =
        List.of("cn/ypbin/starter/", "cn/ypbin/iot/");

    /** 本仓声明 starter 版本的属性名。 */
    private static final String STARTER_VERSION_PROPERTY = "ypbin-starter.version";

    /** 声明 IoT 协议栈版本的属性名（各业务模块 pom 里声明，故全仓扫）。 */
    private static final String IOT_VERSION_PROPERTY = "ypbin-iot.version";

    /** 制品坐标前缀（cn.ypbin 下）。 */
    private static final String STARTER_ARTIFACT_PREFIX = "ypbin-starter";
    private static final String IOT_ARTIFACT_PREFIX = "ypbin-iot";

    /** 本地仓库相对路径（组坐标固定为 cn.ypbin）。 */
    private static final String M2_GROUP_PATH = ".m2/repository/cn/ypbin";

    /**
     * 下界①：必须解析到的下游制品数量（当前基线实测 48 个 {@code 4.0.0} / {@code 0.4.0} 制品）。
     *
     * <p>取「下界」而非精确值：制品随业务增减会腐化；这里只用于防「一个 jar 都没找到 ⇒ 空跑绿」，
     * 真正的覆盖自证是下面的锚点类断言。</p>
     */
    private static final int MIN_VENDOR_JARS = 20;

    /** 下界②：必须解析到的下游顶层类数量（当前基线实测 658 个）。 */
    private static final int MIN_VENDOR_CLASSES = 400;

    /**
     * 锚点类（全限定名）：证明扫描确实覆盖了「最该守」的三个制品。
     *
     * <p>三者的选择各有理由：{@code AvailabilityRules} 属 {@code ypbin-starter-iot}（#296 的下沉目标）、
     * {@code RedisNonceStore} 属 {@code ypbin-starter-sign}（当前唯一已知同名豁免的来源）、
     * {@code ModbusRegisterDecoder} 属 {@code ypbin-iot-protocol-modbus}（#295 的删除对象所在制品）。</p>
     */
    private static final List<String> ANCHOR_CLASSES = List.of(
        "cn.ypbin.starter.iot.availability.AvailabilityRules",
        "cn.ypbin.starter.sign.core.RedisNonceStore",
        "cn.ypbin.iot.protocol.modbus.ModbusRegisterDecoder");

    /**
     * 已知同名豁免：本仓简单名 → 理由。
     *
     * <p><b>判据（加新条目必须先过这一关）</b>：上游同名类与本地类**不是「下沉后的副本」关系**，
     * 即本地类承担的是上游同名类**没有**的职责或语义（不是「同一份实现的旧拷贝」）。若只是旧拷贝，
     * 正确做法是删除本地类并改 import，**不得加豁免**。</p>
     *
     * <p>本清单**必须被实际命中**（见 {@link #allowListEntriesMustStillBeRealCollisions()}）：
     * 一旦本地类被删除/改名，条目即失效 ⇒ 门禁转红，强制同步清理，避免豁免长期放行。</p>
     */
    private static final Map<String, String> ALLOWED_SIMPLE_NAMES = allowedSimpleNames();

    private static Map<String, String> allowedSimpleNames() {
        Map<String, String> allowed = new LinkedHashMap<>();
        // 2026-10-18（issue #295）：开放 API 的 nonce 防重放存储。
        // ypbin-starter-sign 的 RedisNonceStore 用 Lua 脚本实现 SET NX EX，异常直接向上抛；
        // 本仓这份是「开放 API 内部校验链路」专用实现（单条 SET NX EX + fail-closed：Redis 异常按
        // 拒绝处理并带 sanitize 的 error 日志），装配点也是本仓自己的 IotOpenApiSignConfiguration
        // （不走 starter 的 ypbin.sign 自动配置，两者生命周期与开关不同，见该类 javadoc）。
        // ⇒ 不是「旧副本」，而是语义不同的独立实现；删除它会丢掉 fail-closed 与日志口径。
        allowed.put("RedisNonceStore",
            "开放 API 专用实现（fail-closed + sanitized error 日志），与 starter-sign 的 Lua 版语义不同，"
                + "不是旧副本；见 IotOpenApiSignConfiguration 的装配理由");
        return allowed;
    }

    /** 顶格声明的顶层类型（class/interface/enum/record/annotation）：用于覆盖「一个文件多个顶层类型」。 */
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
        "^(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Test
    @DisplayName("★ 门禁：cn.ypbin.admin.* 不得与 cn.ypbin.iot.* / cn.ypbin.starter.* 出现同名类")
    void adminClassesMustNotDuplicateStarterOrIotClassNames() throws IOException {
        Map<String, List<String>> repoTypes = repoTopLevelTypes();
        Map<String, Set<String>> vendorTypes = vendorTopLevelTypes();

        assertThat(repoTypes).as("本仓主源码必须扫到顶层类型（否则是没跑到，不是没问题）").isNotEmpty();
        assertThat(vendorTypes.size())
            .as("下游制品顶层类数量低于下界（%d）：本地仓库缺 jar 或版本过滤写错 ⇒ 门禁会退化成空跑",
                MIN_VENDOR_CLASSES)
            .isGreaterThanOrEqualTo(MIN_VENDOR_CLASSES);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : repoTypes.entrySet()) {
            String simpleName = entry.getKey();
            Set<String> vendors = vendorTypes.get(simpleName);
            if (vendors == null || ALLOWED_SIMPLE_NAMES.containsKey(simpleName)) {
                continue;
            }
            violations.add(simpleName + "：本仓 " + entry.getValue() + " ↔ 下游 " + vendors);
        }
        assertThat(violations).as(
            "发现与 starter/iot 制品**同名**的本地类（下沉后忘记删副本的典型形态；同名会让栈帧/IDE 跳转"
                + "与同包测试解析产生歧义）。请删除本地副本并改为 import 官方实现；"
                + "若本地类确实不是副本（承担上游没有的职责），才允许登记到 ALLOWED_SIMPLE_NAMES 并写明理由。"
                + "违规：%s", violations).isEmpty();
    }

    @Test
    @DisplayName("★ 规则自检：豁免清单的每一条都必须仍是**真实冲突**（清理后必须同步删豁免）")
    void allowListEntriesMustStillBeRealCollisions() throws IOException {
        Map<String, List<String>> repoTypes = repoTopLevelTypes();
        Map<String, Set<String>> vendorTypes = vendorTopLevelTypes();

        Set<String> consumed = new LinkedHashSet<>();
        for (String allowed : ALLOWED_SIMPLE_NAMES.keySet()) {
            if (repoTypes.containsKey(allowed) && vendorTypes.containsKey(allowed)) {
                consumed.add(allowed);
            }
        }
        assertThat(consumed).as(
            "ALLOWED_SIMPLE_NAMES 存在**未命中**的失效条目（本地类已删除/改名，或下游不再提供同名类）："
                + "必须同步删除该豁免，否则门禁会长期放行同类违规").containsExactlyInAnyOrderElementsOf(
            ALLOWED_SIMPLE_NAMES.keySet());
    }

    @Test
    @DisplayName("★ 规则自检：扫到的下游制品必须覆盖三个锚点制品（防「找不到 jar 就当没冲突」）")
    void vendorJarScanMustCoverAnchorArtifacts() throws IOException {
        Map<String, Set<String>> vendorTypes = vendorTopLevelTypes();
        assertThat(vendorTypes.size()).isGreaterThanOrEqualTo(MIN_VENDOR_CLASSES);
        List<String> missing = ANCHOR_CLASSES.stream()
            .filter(anchor -> !vendorTypes.containsKey(simpleNameOf(anchor)))
            .sorted()
            .toList();
        assertThat(missing).as(
            "本地仓库未解析到锚点类 %s ⇒ 扫描范围没有覆盖 starter-iot / starter-sign / "
                + "iot-protocol-modbus。请先构建/安装依赖（`mvn -o install -DskipTests`）或检查 "
                + "`%s` 的版本声明是否与本地仓库一致（禁静默降级：缺依赖时必须失败，不得当成「无冲突」）",
            missing, M2_GROUP_PATH).isEmpty();
    }

    /** 全限定名取简单名。 */
    private static String simpleNameOf(String fullyQualifiedName) {
        int index = fullyQualifiedName.lastIndexOf('.');
        return index < 0 ? fullyQualifiedName : fullyQualifiedName.substring(index + 1);
    }

    /**
     * 本仓主源码声明的**顶层类型**：简单名 → 源码相对路径（同一简单名可能出现在多个模块，
     * 故用列表；跨模块同名同样会让门禁的目标变模糊，一并暴露）。
     *
     * @return 简单名 → 源码位置
     * @throws IOException 读源码失败
     */
    private Map<String, List<String>> repoTopLevelTypes() throws IOException {
        Map<String, List<String>> types = new LinkedHashMap<>();
        for (Path source : SourceScan.mainSources()) {
            String relative = SourceScan.relative(source);
            if (!relative.contains(REPO_PACKAGE_PREFIX)) {
                continue;
            }
            for (String simpleName : topLevelTypeNames(source)) {
                types.computeIfAbsent(simpleName, key -> new ArrayList<>()).add(relative);
            }
        }
        return types;
    }

    /**
     * 源码里的顶层类型名：文件名（对 public 类是唯一事实源）+ 顶格声明的类型。
     *
     * @param source 源码文件
     * @return 类型简单名
     * @throws IOException 读文件失败
     */
    private static List<String> topLevelTypeNames(Path source) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        String fileName = source.getFileName().toString();
        String fromFileName = fileName.substring(0, fileName.length() - ".java".length());
        if (!fromFileName.equals("package-info") && !fromFileName.equals("module-info")) {
            names.add(fromFileName);
        }
        // 剥离注释后只看顶格声明（列 0 起）：嵌套类型必然有缩进，故不会误算成顶层类型。
        String code = Files.readString(source, StandardCharsets.UTF_8)
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("//[^\\n]*", "");
        for (String line : code.split("\\R")) {
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            Matcher matcher = TOP_LEVEL_TYPE.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return List.copyOf(names);
    }

    /**
     * 下游制品（{@code cn.ypbin.*} 包）里的**顶层类**：简单名 → 提供它的 jar 文件名。
     *
     * <p>只认本仓 pom **声明版本**的制品（见类注释），并只取 starter / iot 两个包前缀。</p>
     *
     * @return 简单名 → jar
     * @throws IOException 读本地仓库失败
     */
    private Map<String, Set<String>> vendorTopLevelTypes() throws IOException {
        Path vendorRoot = Path.of(System.getProperty("user.home"), M2_GROUP_PATH);
        assertThat(Files.isDirectory(vendorRoot)).as(
            "找不到本地 Maven 仓库目录 %s（先执行 `mvn -o install -DskipTests` 把依赖装进来；"
                + "本门禁要求可解析依赖，缺依赖时不得静默放行）", vendorRoot).isTrue();

        Map<String, Set<String>> starterVersions = declaredVersions(STARTER_VERSION_PROPERTY);
        Map<String, Set<String>> iotVersions = declaredVersions(IOT_VERSION_PROPERTY);

        List<Path> jars = new ArrayList<>();
        collectJars(vendorRoot, STARTER_ARTIFACT_PREFIX, starterVersions, jars);
        collectJars(vendorRoot, IOT_ARTIFACT_PREFIX, iotVersions, jars);
        assertThat(jars.size()).as("解析到的下游制品数量低于下界（%d）：检查版本声明与本地仓库", MIN_VENDOR_JARS)
            .isGreaterThanOrEqualTo(MIN_VENDOR_JARS);

        Map<String, Set<String>> types = new LinkedHashMap<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || !isVendorPackage(name)) {
                        continue;
                    }
                    String fileName = name.substring(name.lastIndexOf('/') + 1);
                    // 跳过内部类/匿名类（$）与 package-info/module-info：只有顶层类型才谈「同名类」。
                    if (fileName.contains("$") || fileName.startsWith("package-info")
                        || fileName.startsWith("module-info")) {
                        continue;
                    }
                    types.computeIfAbsent(fileName.substring(0, fileName.length() - ".class".length()),
                        key -> new LinkedHashSet<>()).add(jar.getFileName().toString());
                }
            }
        }
        return types;
    }

    /** 类文件是否属于 {@code cn.ypbin.starter.*} 或 {@code cn.ypbin.iot.*} 包。 */
    private static boolean isVendorPackage(String classEntryPath) {
        for (String prefix : VENDOR_PACKAGE_PREFIXES) {
            if (classEntryPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集某坐标前缀下「版本 ∈ 本仓声明版本」的 jar。
     *
     * @param vendorRoot     本地仓库 cn.ypbin 目录
     * @param artifactPrefix 坐标前缀（{@code ypbin-starter} / {@code ypbin-iot}）
     * @param versions       本仓声明的版本集合（属性名 → 版本）
     * @param sink           结果
     * @throws IOException 遍历失败
     */
    private static void collectJars(Path vendorRoot, String artifactPrefix,
                                    Map<String, Set<String>> versions, List<Path> sink)
        throws IOException {
        Set<String> accepted = new LinkedHashSet<>();
        versions.values().forEach(accepted::addAll);
        assertThat(accepted).as("本仓 pom 未解析出 %s 的版本声明", artifactPrefix).isNotEmpty();
        try (Stream<Path> artifacts = Files.list(vendorRoot)) {
            for (Path artifactDir : artifacts.toList()) {
                String artifactId = artifactDir.getFileName().toString();
                if (!artifactId.startsWith(artifactPrefix) || !Files.isDirectory(artifactDir)) {
                    continue;
                }
                try (Stream<Path> versionDirs = Files.list(artifactDir)) {
                    for (Path versionDir : versionDirs.toList()) {
                        if (!accepted.contains(versionDir.getFileName().toString())
                            || !Files.isDirectory(versionDir)) {
                            continue;
                        }
                        try (Stream<Path> files = Files.list(versionDir)) {
                            files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                                // 只认主构件：sources/javadoc/tests 分类器不含运行期类（且会导致重复计数）
                                .filter(path -> {
                                    String fileName = path.getFileName().toString();
                                    return !fileName.contains("-sources")
                                        && !fileName.contains("-javadoc")
                                        && !fileName.contains("-tests");
                                })
                                .forEach(sink::add);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从本仓 pom 里解析指定属性的**字面**版本值。
     *
     * <p>扫全部模块 pom（{@code ypbin-iot.version} 声明在业务子模块而不是根 pom）。
     * 属性值必须是纯字面量：写成 {@code ${...}} 时无法判定版本 ⇒ 显式失败（不猜）。</p>
     *
     * @param propertyName 属性名
     * @return 属性名（所在 pom 相对路径）→ 版本集合
     * @throws IOException 读 pom 失败
     */
    private static Map<String, Set<String>> declaredVersions(String propertyName) throws IOException {
        Map<String, Set<String>> versions = new LinkedHashMap<>();
        List<Path> poms;
        try (Stream<Path> paths = Files.walk(SourceScan.repoRoot())) {
            // 全部模块 pom（含聚合子模块）：`ypbin-iot.version` 声明在业务子模块而不是根 pom。
            // 跳过构建产物与嵌套检出（`target`/`.git`）：那些不是本仓声明。
            poms = paths.filter(path -> path.getFileName().toString().equals("pom.xml"))
                .filter(path -> {
                    String relative = SourceScan.relative(path);
                    return !relative.contains("/target/") && !relative.contains("/.git/")
                        && !relative.startsWith("target/");
                })
                .sorted()
                .toList();
        }
        Pattern property = Pattern.compile("<" + Pattern.quote(propertyName) + ">\\s*([^<]+?)\\s*</"
            + Pattern.quote(propertyName) + ">");
        for (Path pom : poms) {
            Matcher matcher = property.matcher(Files.readString(pom, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String value = matcher.group(1).trim();
                assertThat(value).as("%s 的 %s 必须是字面版本号，实际为 %s（无法判定版本时不得猜）",
                    SourceScan.relative(pom), propertyName, value).doesNotContain("${");
                versions.computeIfAbsent(SourceScan.relative(pom), key -> new LinkedHashSet<>()).add(value);
            }
        }
        return versions;
    }
}
