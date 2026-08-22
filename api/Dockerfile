FROM golang:1.26-alpine AS build
WORKDIR /src
# GOWORK=off: a parent go.work outside this module must not affect the build.
ENV GOWORK=off
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/api ./cmd/api

FROM alpine:3.20
# ffprobe is used to measure audio duration during the download stage.
RUN apk add --no-cache ffmpeg ca-certificates curl tzdata
COPY --from=build /out/api /usr/local/bin/api
ENV AUDIO_DIR=/data/audio RAW_DIR=/data/raw DATA_CACHE_DIR=/data/cache
EXPOSE 8080
ENTRYPOINT ["api"]
CMD ["serve"]
