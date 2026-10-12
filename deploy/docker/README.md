# Docker deployment

```bash
cp .env.example .env
```

Change the token in `.env`, then:

```bash
docker compose up -d --build
docker compose ps
```

Open `http://SERVER-IP:8770`. Persistent database, snapshots, original saves,
and generated saves are stored under `./docker-data` by default.
