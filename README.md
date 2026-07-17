# MemberRoll

A Tomcat/Jersey/Keycloak webapp (from webapp-template).

- Getting started (dev loop, LAN phones, production): [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)
- Server details and Keycloak primer: [server/README.md](server/README.md)
- Deployment: [server/deploy/README.md](server/deploy/README.md)
- Workflow: [docs/change-requests/](docs/change-requests/)

Dev loop:

```bash
mvn clean package
(cd server && docker compose up -d)   # Keycloak :8081
mvn -pl server cargo:run              # Tomcat :8080 → http://localhost:8080/server/web/
```
