FROM eclipse-temurin:21-jre
WORKDIR /app
# 可执行 jar（revision 版本名，通配匹配；排除 .original 原始包）
COPY target/ypbin-access-*.jar /app/app.jar
# 接入单元是有状态的（持有长连接/会话），GC 目标与业务服务不同：优先低延迟、堆给足一些
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms256m -Xmx512m" \
    TZ=Asia/Shanghai
EXPOSE 18086
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
