# Сервер Voxelin у Docker (без вікна): docker build -t voxelin-server . && docker run -it -p 24555:24555 -v voxelin-data:/data voxelin-server
# Усе своє (server.properties з кодом, saves/, mods/, config/) - у /data; параметри сервера - після назви образу (--world, --survival, ...).
# Консоль: docker attach <контейнер> (вийти, не зупиняючи, - Ctrl+P Ctrl+Q); docker stop - світ зберігається.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY build.sh ./
COPY lib lib
COPY src src
RUN ./build.sh && rm -f lib/*natives-windows*.jar

FROM eclipse-temurin:21-jre
COPY --from=build /src/out /opt/voxelin/out
COPY --from=build /src/lib /opt/voxelin/lib
WORKDIR /data
VOLUME /data
EXPOSE 24555
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", "/opt/voxelin/out:/opt/voxelin/lib/*", "voxelin.server.DedicatedServer"]
