# v6: 앱 이미지 (로컬에서 빌드한 jar 복사)
# 빌드: ./mvnw package -Dmaven.test.skip=true
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/coupon-interface-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
