FROM mcr.microsoft.com/dotnet/sdk:10.0-alpine AS engine-build
WORKDIR /src
COPY engine/ThorVault.Engine/ThorVault.Engine.csproj ./engine/ThorVault.Engine/
RUN dotnet restore ./engine/ThorVault.Engine/ThorVault.Engine.csproj
COPY engine/ThorVault.Engine/Program.cs ./engine/ThorVault.Engine/
RUN dotnet publish ./engine/ThorVault.Engine/ThorVault.Engine.csproj \
    --configuration Release --no-restore --output /publish

FROM mcr.microsoft.com/dotnet/runtime:10.0-alpine
RUN apk add --no-cache python3
WORKDIR /opt/thorvault
COPY app ./app
COPY server/server.py ./server/server.py
COPY --from=engine-build /publish ./engine-publish
RUN mkdir -p /data/thorvault /data/roms /data/saves
ENV THORVAULT_DATA=/data/thorvault \
    THORVAULT_ROMS=/data/roms \
    THORVAULT_SAVES=/data/saves \
    THORVAULT_ENGINE_DLL=/opt/thorvault/engine-publish/ThorVault.Engine.dll \
    THORVAULT_HOST=0.0.0.0 \
    THORVAULT_PORT=8765
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/health', timeout=3).read()" || exit 1
CMD ["python3", "server/server.py"]
