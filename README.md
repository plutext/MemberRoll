# MemberRoll

A Tomcat/Jersey/Keycloak webapp (from webapp-template).

- Roadmap and scope decisions: [docs/ROADMAP.md](docs/ROADMAP.md)
- Getting started (dev loop, LAN phones, production): [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)
- Server details and Keycloak primer: [server/README.md](server/README.md)
- Deployment: [server/deploy/README.md](server/deploy/README.md)
- Workflow: [docs/change-requests/](docs/change-requests/)

Dev loop:

```bash
mvn clean package
(cd server && docker compose up -d)   # Keycloak :18081 + Postgres :5433
mvn -pl server cargo:run              # Tomcat :18080 → http://localhost:18080/server/web/
```

## License

Copyright 2026 Jason Harrop. Licensed under the
[Apache License, Version 2.0](LICENSE).
