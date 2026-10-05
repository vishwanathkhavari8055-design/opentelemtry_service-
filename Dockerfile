# Base image with Java 25 on Alpine Linux
FROM repolocal.trinityiot.in/tsf/trinitybase/jarbaseimg-eclipse-temurin:25.0.0
 
# Copy JAR file to /opt directory
COPY target/*.jar /opt/
 
EXPOSE 8080 8688 8089 8443
 
# Run the JAR file with environment and JAVA_OPTS
CMD ["sh", "-c", "java $JAVA_OPTS -DVaultUrl=$VaultURL -Djavamelody.graphite-address=graphite:2003 -DXVaultToken=$VaultToken -DEnv=$Env -DVersion=$Version -jar /opt/*.jar"]
