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

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * iot 模块的 MyBatis 注解 SQL **XML 合法性**门禁（类级，替代原先只钉一个方法的点覆盖）。
 *
 * <p><b>为什么需要它（真实生产事故）</b>：{@code @Insert("<script>…")} 少了结尾 {@code </script>} 时，
 * MyBatis 在**应用启动**时就把该注解当 XML 解析，直接抛
 * {@code SAXParseException: XML document structures must start and end within the same entity}
 * ⇒ Mapper bean 建不出来 ⇒ 整个 iot 服务起不来（2026-09-25 生产部署实测：新镜像起不来，
 * 靠旧镜像 tag 回滚才恢复，见 {@code ShadowReportedMapperContractTest} 的同类断言）。
 * 单测全部 mock Mapper、不建 {@code SqlSessionFactory}，**咬不到注解解析**；原有断言只覆盖
 * {@code IotShadowMapper.mergeReported} 一个方法 ⇒ 同类问题出现在别的 Mapper 仍无门禁。</p>
 *
 * <p><b>本门禁怎么咬</b>：对 iot 模块**编译产物里的全部 Mapper 接口**逐个调用
 * {@link Configuration#addMapper(Class)} —— 这正是应用启动时 {@code MapperRegistry} →
 * {@code MapperAnnotationBuilder.parse()} 走的那条路径（注解串的拼接、{@code @Lang} 选择、
 * {@code XMLLanguageDriver.createSqlSource} 全部由 MyBatis 自己做，不在这里复刻任何一行），
 * 任何一个 {@code <script>} 不合法都会当场抛异常 ⇒ 门禁转红。</p>
 *
 * <p><b>为什么读字节码而不是扫源码</b>：源码正则挡不住「注解值由常量拼接」「文本块」「续行」等写法，
 * 也容易把注释里的示例算进来；字节码里是 MyBatis 真正看到的注解值。模块 {@code target/classes}
 * 缺失时**显式失败**而不是缩水扫描（禁静默降级）——所以本门禁必须在「先建业务模块」之后跑（独立跑本模块时
 * `-am` **不包含** {@code ypbin-iot}，因为前者不是后者的 Maven 依赖）：</p>
 * <pre>{@code
 * # ① 先建业务模块（产出 ypbin-service/ypbin-iot/target/classes）
 * mvn -pl ypbin-service/ypbin-iot -am test
 * # ② 再跑本门禁
 * mvn -pl ypbin-architecture-tests -am test
 * }</pre>
 * <p>全反应堆 {@code mvn clean verify} 里两者顺序由聚合 pom 保证（{@code ypbin-service} 在
 * {@code dev-only} profile 的 {@code ypbin-architecture-tests} 之前），无需手工两步。</p>
 *
 * <p><b>覆盖范围的自证</b>：① 断言扫到的 Mapper 数不低于下界，且字节码里带 SQL 注解的方法数**等于**源码
 * 注解数、每个都产出了语句（自校准，防「0 违规 = 没跑到」，本仓教训七/八）；② 另有一条源码级断言证明
 * 「iot 模块所有 MyBatis SQL 注解都落在 {@code mapper} 包内」⇒ 字节码扫描范围不会因文件搬家而静默漏掉目标。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
class IotMapperAnnotationXmlGateTest {

    /** 被门禁的模块（注解 SQL 的产地）。 */
    private static final Path IOT_MODULE = Path.of("ypbin-service", "ypbin-iot");

    /** Mapper 包在编译产物里的相对路径。 */
    private static final String MAPPER_PACKAGE_PATH = "cn/ypbin/admin/iot/mapper";

    /** 源码级覆盖自证用的注解形态（只看代码，不看注释；{@code (} 排除了 {@code @DeleteMapping} 等）。 */
    private static final Pattern SQL_ANNOTATION = Pattern.compile("@(Select|Insert|Update|Delete)\\(");

    /** 字节码侧要核对的注解类型（与源码侧的正则必须一一对应）。 */
    private static final List<Class<? extends Annotation>> SQL_ANNOTATION_TYPES =
        List.of(Select.class, Insert.class, Update.class, Delete.class);

    /**
     * 覆盖下界：至少扫到这么多 Mapper 接口（对 {@code mapper} 包下 19 个接口留足余量）。
     *
     * <p>取「下界」而不是「实测精确值」：精确数字会随业务增减而腐化，导致门禁要么假红要么被放宽
     * （本仓文档已因「会腐化的数字」翻过车）。真正防「没跑到」的是下面**自校准**的那条断言
     * ——「解析出的语句数」必须等于「源码里带 SQL 注解的方法数」。</p>
     */
    private static final int MIN_MAPPERS = 12;

    @Test
    @DisplayName("★ 类级门禁：iot 模块每个 Mapper 的注解 SQL 都必须能被 MyBatis 按启动路径解析")
    void everyIotMapperAnnotationMustBeParsable() throws Exception {
        Path classesDir = SourceScan.repoRoot().resolve(IOT_MODULE).resolve(SourceScan.CLASSES_DIR);
        assertThat(Files.isDirectory(classesDir))
            .as("找不到 %s ⇒ 本门禁会退化成空跑。请先构建业务模块（`mvn -pl ypbin-service/ypbin-iot -am test`）"
                + "再跑架构门禁", SourceScan.relative(classesDir))
            .isTrue();

        List<String> mapperClassNames = mapperClassNames(classesDir);
        assertThat(mapperClassNames).as("iot 模块 mapper 包下必须扫到 Mapper 接口（否则是没跑到，不是没问题）")
            .hasSizeGreaterThanOrEqualTo(MIN_MAPPERS);
        int annotatedMethods = annotatedMapperMethodCount();
        assertThat(annotatedMethods).as("iot 模块 mapper 包里必须有带 SQL 注解的方法（否则门禁没有目标）")
            .isPositive();

        List<String> violations = new ArrayList<>();
        int checkedMethods = 0;
        try (URLClassLoader loader = new URLClassLoader(moduleClasspath(classesDir),
            IotMapperAnnotationXmlGateTest.class.getClassLoader())) {
            for (String className : mapperClassNames) {
                Class<?> mapper = load(loader, className);
                Configuration configuration = new Configuration();
                try {
                    configuration.addMapper(mapper);
                } catch (RuntimeException ex) {
                    violations.add(className + " → " + rootMessage(ex));
                    continue;
                }
                // 逐方法核对「注解确实被解析成了语句」：比只数总数更精确，也不受 MyBatis
                // 内部别名（短名/全名）影响。这里的 id 构造与 MyBatis 的
                // MapperBuilderAssistant.applyCurrentNamespace 口径一致（namespace + "." + 方法名）。
                for (Method method : mapper.getDeclaredMethods()) {
                    if (!hasSqlAnnotation(method)) {
                        continue;
                    }
                    checkedMethods++;
                    String statementId = mapper.getName() + "." + method.getName();
                    if (!configuration.hasStatement(statementId, false)) {
                        violations.add(statementId + " → 注解未被解析成语句（扫描/解析没真正发生）");
                    }
                }
            }
        }
        assertThat(violations).as("MyBatis 启动解析失败 = 服务起不来，必须修注解本身：%s", violations).isEmpty();
        // 自校准的覆盖证明（防「0 违规 = 没跑到」）：源码里数出来的注解数必须与字节码里带注解的方法数
        // 一致，且每个都产出了语句。不一致只有两种原因：编译产物陈旧（先重建业务模块）或目标被漏扫
        // （门禁被解除武装）——两种都必须当场暴露。
        assertThat(checkedMethods)
            .as("字节码里带 SQL 注解的方法数必须等于源码注解数（%d）：不等说明扫描没覆盖全部目标，"
                + "或业务模块没重建", annotatedMethods)
            .isEqualTo(annotatedMethods);
    }

    /** 方法上是否带 MyBatis 的 SQL 注解（{@code @Select/@Insert/@Update/@Delete}）。 */
    private static boolean hasSqlAnnotation(Method method) {
        for (Class<? extends Annotation> type : SQL_ANNOTATION_TYPES) {
            if (method.getAnnotation(type) != null) {
                return true;
            }
        }
        return false;
    }

    /** iot 模块 mapper 包里带 SQL 注解的方法数（源码计数，注释已剥离；用于自校准覆盖证明）。 */
    private static int annotatedMapperMethodCount() throws IOException {
        int count = 0;
        for (Path source : SourceScan.mainSources()) {
            if (!isIotMapperSource(source) || !isMapperPackage(source)) {
                continue;
            }
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            Matcher matcher = SQL_ANNOTATION.matcher(code);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("★ 覆盖自证：iot 模块所有 MyBatis SQL 注解都在 mapper 包内（字节码扫描范围不会漏目标）")
    void allAnnotatedSqlMustLiveInMapperPackage() throws IOException {
        List<String> outside = new ArrayList<>();
        for (Path source : SourceScan.mainSources()) {
            if (!isIotMapperSource(source) || isMapperPackage(source)) {
                continue;
            }
            // 教训二十三：文本门禁必须作用在剥离注释后的代码上，否则 Javadoc 里的示例会制造误报
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            if (SQL_ANNOTATION.matcher(code).find()) {
                outside.add(SourceScan.relative(source));
            }
        }
        assertThat(outside).as(
            "在这些文件里发现了 MyBatis SQL 注解，但本门禁只扫 mapper 包 ⇒ 请把该文件纳入扫描范围"
                + "（不要静默漏掉）").isEmpty();
    }

    @Test
    @DisplayName("★ 规则自检：缺 </script> 的注解必须被判为非法，合法 XML 与非 XML 文本不得误报")
    void ruleMustActuallyDetectMissingClosingTag() {
        // 生产事故同款：只少了结尾标签，MyBatis 抛的就是这条 SAXParseException
        assertThat(parseFailureOf(SyntheticMalformedScriptMapper.class))
            .as("缺 </script> 必须被咬到（否则本门禁就是装饰）")
            .isNotNull()
            .contains("SAXParseException")
            .contains("XML document structures must start and end within the same entity");
        // 合法 XML（含 foreach 等动态标签）不得误报
        assertThat(parseFailureOf(SyntheticWellFormedScriptMapper.class))
            .as("合法的 <script> 注解不得被判为违规").isNull();
        // 非 <script> 的文本 SQL：MyBatis 不按 XML 解析，门禁也不得误报
        assertThat(parseFailureOf(SyntheticPlainSqlMapper.class))
            .as("文本 SQL（含裸 < 比较符）不是 XML，门禁不能误报").isNull();
    }

    /** 源码是否属于 iot 模块。 */
    private static boolean isIotMapperSource(Path source) {
        return SourceScan.relative(source)
            .startsWith(IOT_MODULE.toString().replace(File.separatorChar, '/') + "/");
    }

    /** 源码是否在 mapper 包内。 */
    private static boolean isMapperPackage(Path source) {
        return SourceScan.relative(source).contains("/" + MAPPER_PACKAGE_PATH + "/");
    }

    /** 用与主门禁完全相同的机制解析一个 Mapper，返回失败原因（成功返回 {@code null}）。 */
    static String parseFailureOf(Class<?> mapper) {
        try {
            new Configuration().addMapper(mapper);
            return null;
        } catch (RuntimeException ex) {
            return rootMessage(ex);
        }
    }

    /** mapper 包下的类名（跳过内部类与匿名类）。 */
    private static List<String> mapperClassNames(Path classesDir) throws IOException {
        Path packageDir = classesDir.resolve(MAPPER_PACKAGE_PATH);
        if (!Files.isDirectory(packageDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(packageDir)) {
            return paths.filter(path -> path.toString().endsWith(".class"))
                .map(path -> classesDir.relativize(path).toString().replace(File.separatorChar, '/'))
                .filter(name -> !name.contains("$"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .sorted()
                .toList();
        }
    }

    /** iot 模块及其它本仓模块的 {@code target/classes}（Mapper 的返回类型可能来自别的模块）。 */
    private static URL[] moduleClasspath(Path iotClassesDir) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(iotClassesDir.toUri().toURL());
        for (Path moduleRoot : SourceScan.sourceModuleRoots()) {
            Path classesDir = moduleRoot.resolve(SourceScan.CLASSES_DIR);
            if (Files.isDirectory(classesDir) && !classesDir.equals(iotClassesDir)) {
                urls.add(classesDir.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    /** 加载类：只加载不初始化（Maven 编译产物里没有需要跑静态初始化的前提）。 */
    private static Class<?> load(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            throw new IllegalStateException("加载 Mapper " + className + " 失败：门禁无法判定它是否合法，"
                + "按失败处理（不静默跳过）", ex);
        }
    }

    /** 异常链最内层的消息（MyBatis 会把 SAXParseException 暴露在这一层）。 */
    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 剥离行注释与块注释（门禁必须作用在代码上，注释里的示例不得参与判定）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
