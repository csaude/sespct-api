# Base semelhante ao mentoring (GraalVM + Java 17)
FROM ghcr.io/graalvm/graalvm-ce:ol7-java17-21.3.0

# Pasta padrão do projeto
WORKDIR /his/sespct/backend

# Pastas úteis (logs/dados)
RUN mkdir -p /his/sespct/backend/log /his/sespct/backend/data

# Volumes para logs e (se precisares) dados temporários
VOLUME ["/his/sespct/backend/log", "/his/sespct/backend/data"]

# Opções JVM e ambiente Micronaut (podem ser sobrepostas no 'docker run')
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV MICRONAUT_ENVIRONMENTS=production

# Copia o fat JAR (gera antes com: ./gradlew shadowJar)
# Mantém o nome estável dentro da imagem
COPY build/libs/*-all.jar /his/sespct/backend/sespct-api-all.jar

# Porta da app (conforme application.yml)
EXPOSE 8383

# Arranque
CMD ["sh", "-c", "java $JAVA_OPTS -jar /his/sespct/backend/sespct-api-all.jar"]
