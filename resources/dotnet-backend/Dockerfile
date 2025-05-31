FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS base

RUN apt-get update && apt-get install -y \
    xvfb \
    libgdiplus \
    fontconfig \
    fonts-liberation \
    fonts-thai-tlwg \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN fc-cache -fv

ENV DISPLAY=:99
ENV LANG=en_US.UTF-8

EXPOSE 8080
EXPOSE 8081

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
ARG BUILD_CONFIGURATION=Release
ARG PROJECT_NAME
WORKDIR /src
COPY ["${PROJECT_NAME}", "."]
RUN dotnet restore "./${PROJECT_NAME}"
COPY . .
WORKDIR "/src/."
RUN dotnet build "./${PROJECT_NAME}" -c $BUILD_CONFIGURATION -o /app/build

FROM build AS publish
ARG BUILD_CONFIGURATION=Release
ARG PROJECT_NAME
RUN dotnet publish "./${PROJECT_NAME}" -c $BUILD_CONFIGURATION -o /app/publish /p:UseAppHost=false

FROM base AS final
ARG PROJECT_DLL_NAME
WORKDIR /app
COPY --from=publish /app/publish .

RUN echo "#!/bin/bash\nXvfb :99 -screen 0 1024x768x24 &\nsleep 2\ndotnet $PROJECT_DLL_NAME" > /app/entrypoint.sh && \
    chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]