FROM eclipse-temurin:25-jre-alpine
COPY build/libs/beans-service-*-all.jar beans-service.jar
EXPOSE 8080
CMD java -Dcom.sun.management.jmxremote -noverify ${JAVA_OPTS} -jar beans-service.jar