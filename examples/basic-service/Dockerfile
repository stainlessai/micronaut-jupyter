FROM eclipse-temurin:25-jre-alpine
COPY build/libs/basic-service-*-all.jar basic-service.jar
EXPOSE 8080
CMD java -Dcom.sun.management.jmxremote -noverify ${JAVA_OPTS} -jar basic-service.jar