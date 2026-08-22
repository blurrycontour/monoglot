# `make` is not installed on every homelab box, so every target here is a thin
# wrapper around a script that also works on its own.
.PHONY: bootstrap up down logs ingest apk clean test

bootstrap:
	./bootstrap.sh

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f api worker

ingest:
	docker compose run --rm api ingest

apk:
	./scripts/android.sh assembleDebug

test:
	cd api && GOWORK=off go test ./...

clean:
	docker compose down -v
	rm -rf data/audio/* data/raw/*
