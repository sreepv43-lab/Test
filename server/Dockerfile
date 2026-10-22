# StreamHub Server for NAS boxes and other Docker hosts (x86-64).
# Build from the unzipped streamhub-server folder:  docker build -t streamhub-server .
# Run:  docker run -d --name streamhub --restart unless-stopped --network host \
#         -v /path/to/appdata:/data -v /path/to/movies:/downloads streamhub-server
FROM eclipse-temurin:17-jre
COPY . /opt/streamhub-server
ENV STREAMHUB_DATA=/data \
    STREAMHUB_STORAGE="Server storage=/downloads"
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
VOLUME ["/data", "/downloads"]
EXPOSE 8095
ENTRYPOINT ["/opt/streamhub-server/bin/streamhub-server"]
